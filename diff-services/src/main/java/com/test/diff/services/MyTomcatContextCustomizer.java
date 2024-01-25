package com.test.diff.services;

import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.core.StandardWrapper;
import org.springframework.boot.web.embedded.tomcat.TomcatContextCustomizer;

public class MyTomcatContextCustomizer implements TomcatContextCustomizer {

    private final String docBase;

    public MyTomcatContextCustomizer(String docBase) {
        this.docBase = docBase;
    }

    @Override
    public void customize(Context context) {
        context.setDocBase(docBase);
        Wrapper wrapper = (Wrapper) context.findChild("default");
        if (wrapper == null) {
            wrapper = new StandardWrapper();
            wrapper.setServletClass("org.apache.catalina.servlets.DefaultServlet");
            wrapper.setName("report");
            context.addChild(wrapper);
        }
        wrapper.addInitParameter("listings", "true");
        wrapper.addInitParameter("readonly", "true");
        wrapper.addMapping("/code-diff/*");
    }
}
