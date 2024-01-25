package com.test.diff.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.stereotype.Component;

@Component
public class MyWebServerFactoryCustomizer implements org.springframework.boot.web.server.WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    @Value("${docBase:/root}")
    private String docBase;

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        factory.addContextCustomizers(new MyTomcatContextCustomizer(docBase));
    }
}
