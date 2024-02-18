package org.jesterj.index.refguide;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jesterj.ingest.model.Document;
import org.jesterj.ingest.model.DocumentProcessor;
import org.jesterj.ingest.model.impl.NamedBuilder;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class RemoveNavsProcessor implements DocumentProcessor {

    static Logger log = LogManager.getLogger();
    @SuppressWarnings("unused")
    private String name;

    @Override
    public Document[] processDocument(Document document) {
        try {
            org.jsoup.nodes.Document parsed =
                    Jsoup.parse(new ByteArrayInputStream(document.getRawData()),
                            StandardCharsets.UTF_8.name(), "/");
            Elements navs = parsed.select("nav");
            for (Element nav : navs) {
                nav.remove();
            }
            document.setRawData(parsed.outerHtml().getBytes());
            return new Document[]{document};
        } catch (IOException e) {
            log.warn("Dropping unparsable html (or non-html) document)");
            return new Document[0];
        }
    }

    @Override
    public String getName() {
        return null;
    }

    // Builders for classes this simple are mostly boilerplate. Typically, just copy a simple one like this and
    // replace the class names to match the current file.

    public static class Builder extends NamedBuilder<RemoveNavsProcessor> {

        RemoveNavsProcessor obj = new RemoveNavsProcessor();

        protected RemoveNavsProcessor getObj() {
            return obj;
        }

        public RemoveNavsProcessor.Builder named(String name) {
            getObj().name = name;
            return this;
        }

        private void setObj(RemoveNavsProcessor obj) {
            this.obj = obj;
        }

        public RemoveNavsProcessor build() {
            RemoveNavsProcessor object = getObj();
            setObj(new RemoveNavsProcessor());
            return object;
        }

    }
}
