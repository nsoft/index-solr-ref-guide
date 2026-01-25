#!/bin/bash

# Script to setup and maintain a solr instance searching the solr ref guide on localhost
# Running this script will always update the indexed ref guide docs to the latest
# version available on github on the main branch. Solr will become available on ports
# 8981 through 8984 and the ref guide should be searchable in a collection named

### Pre-requisites
#  - zookeeper accessible via localhost:2181
#  - able to modify current directory
#  - current directory is the top of a checkout of https://github.com/nsoft/index-solr-ref-guide/
#  - able to reach github with git clone or git pull command (no auth req)
#  - ports 5001-5004, 7981-7984 and 8981-8984 are not used for other purposes (used by solr)
#  - port 7000 and 9042 must be open (used by JesterJ cassandra)
#  - able to create/modify contents of ~/.jj (jesterj puts logs and cassandra db here)
#  - jq command line utility must be installed
#  - java 11 must be available at $JAVA_11_HOME (required for -j only)

# Usage:
#   get-solr.sh [options]
#
# Options:
#   -S  Solr bleeding edge needs to be (re)started
#   -j  JesterJ official released version needs to be (re)started

# note -s for running solr current release is complicated by incompatibility of cloud.sh across
# solr versions so that will take more effort and is not currently available.

#
#  Case 1: first time setup, released JesterJ - download install and start
#
#    get-solr-sh -Sj

#  Case 2: Freshen solr docs
#
#    get-solr.sh


SOLR_ACTION="none"  # by default continue with existing Solr server
JJ_ACTION="none"    # by default assume JesterJ already running

if [ -z "$JAVA_11_HOME" ]; then
  echo "Please set the JAVA_11_HOME environment variable"
  exit 3
fi

# pass -s to download or refresh Solr
# pass -j to download and start JesterJ
# pass -J to clone, build and start JesterJ
# Note: -j and -J are mutially exclusive last one wins.
while getopts "sSjJ" arg; do
  case $arg in
  s) SOLR_ACTION="download"
    echo "-s for downloading Solr Release not yet supported"
    exit 1
    ;;
  S) SOLR_ACTION="build";;
  j) JJ_ACTION="download";;
  J) JJ_ACTION="build"
    echo "-J for building JesterJ latest is not yet supported"
    exit 1
    ;;
  esac
done

# Obtain the latest solr code
if [ ! -d "solr/code/solr" ]; then
  echo "checking out solr..."
  mkdir -p "solr/code"
  pushd solr/code
  git clone https://github.com/apache/solr.git
  popd
else
  echo "updating solr..."
  pushd solr/code/solr
  git pull
  popd
fi

if [ "$SOLR_ACTION" = "build" ]; then
  # Build the latest solr code
  echo "Building solr..."
  if [ ! -f "solr/cloud/cloud.sh" ]; then
    echo "For the first time!"
    mkdir -p "solr/cloud"
    cp solr/code/solr/dev-tools/scripts/cloud.sh solr/cloud
    sed -i -e "s/DEFAULT_VCS_WORKSPACE='..\/solr'/DEFAULT_VCS_WORKSPACE='..\/code\/solr'/g" solr/cloud/cloud.sh
    pushd solr/cloud
    ./cloud.sh new -r refguide-index
    popd
  else
    echo "Again.."
    pushd solr/cloud
    ./cloud.sh restart -r refguide-index
    popd
  fi
fi

SOLR_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8981/solr/admin/info/system)
SOLR_INFO="unknown"
ZK_HOST="unknown"

if [ ! "200" == "$SOLR_STATUS" ]; then
  echo "Solr not started or not allowing access to solr/admin/info"
else
  SOLR_INFO=$(curl -s http://localhost:8981/solr/admin/info/system?wt=json)
  ZK_HOST=$(echo $SOLR_INFO | jq -r .zkHost)
fi

if [ "unknown" = "$ZK_HOST" ]; then
  echo "Unable to find zkHost in "
  echo "$SOLR_INFO"
  exit 2
else
  echo "Solr's zookeeper = $ZK_HOST"
fi

echo "Uploading latest configset"
# apply the latest configuration to solr
./gradlew upconfig -DzkHost=$ZK_HOST

# create collection if it doesn't exist
readarray -t cols < <(curl -s "http://localhost:8981/solr/admin/collections?action=LIST&wt=json" | jq -r '.collections[]')

if ! printf '%s\0' "${cols[@]}" | grep -Fxqz -- 'ref_guide'; then
  echo "We need to create the ref_guide collection!"

  curl  -s -X POST http://localhost:8981/api/collections?pretty=true -H 'Content-Type: application/json' -d '
      {
        "name": "ref_guide",
        "config": "ref-guide",
        "numShards": 4
      }
  ' | jq
else
  echo "ref_guide collection exists, reloading"
  curl  -s -X POST http://localhost:8983/api/collections/ref_guide/reload | jq
fi

# We now have the latest solr, up and running and thus also a checkout of the latest ref guide, time to build it!

pushd solr/code/solr
./gradlew buildLocalSite
popd

# we now have a ref guide at solr/code/solr/solr/solr-ref-guide/site/index.html

if [ "download" = "$JJ_ACTION" ]; then

  # time to build our jar!
  JAVA_HOME="$JAVA_11_HOME" bash -c './gradlew packageUnoJar'

  NODE_JAR=$(find . -maxdepth 1 -name 'jesterj-ingest-*-node.jar')

  if [ -f "$NODE_JAR" ]; then
    echo "JesterJ node jar is present at $NODE_JAR"
  else
    echo "JesterJ node jar not found, downloading..."
    curl -sL  -o "jesterj-ingest-1.0.0-node.jar" "https://github.com/nsoft/jesterj/releases/download/1.0.0/jesterj-ingest-1.0.0-node.jar"
  fi

  nohup $JAVA_11_HOME/bin/java -jar -DzkHost=$ZK_HOST jesterj-ingest-1.0.0-node.jar build/libs/index-solr-ref-guide-1.0-SNAPSHOT-dep.jar solrrefguide s3cret > jj.output.log &

  echo "JesterJ startup attempted check jj.output.log and  ~/.jj/logs for details"
fi

echo "Solr Reference Guide should now match the latest head (SNAPSHOT) version"