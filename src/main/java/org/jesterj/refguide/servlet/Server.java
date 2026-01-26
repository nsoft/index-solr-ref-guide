package org.jesterj.refguide.servlet;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

public class Server {

  public void start() throws Exception {
    org.eclipse.jetty.server.Server server = new org.eclipse.jetty.server.Server();
    ServerConnector connector = new ServerConnector(server);
    connector.setPort(8980);
    server.setConnectors(new Connector[]{connector});

    ServletContextHandler ctx = new ServletContextHandler();
    ctx.setContextPath("/");
    ServletHolder proxyHolder = ctx.addServlet(SearchProxy.class, "/search/*");

    // Add the DefaultServlet to serve static content.
    ServletHolder staticHolder = ctx.addServlet(DefaultServlet.class, "/");
    staticHolder.setInitParameter("resourceBase", "solr/code/solr/solr/solr-ref-guide/build/site");
    staticHolder.setAsyncSupported(true);

    ctx.setWelcomeFiles(new String[]{"index.html"});

    server.setHandler(ctx);
    server.start();
    server.join();
  }

  public static void main(String[] args) throws Exception {
    new Server().start();
  }
}
