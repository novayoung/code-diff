package com.test.diff.services;

import com.test.diff.services.enhance.JacgEnhancer;
import com.test.diff.services.utils.SpringUtil;
import org.apache.ibatis.javassist.ClassClassPath;
import org.apache.ibatis.javassist.ClassPool;
import org.apache.ibatis.javassist.CtClass;
import org.apache.ibatis.javassist.CtMethod;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

@SpringBootApplication
@MapperScan(basePackages = "com.test.diff.services.mapper")
@Configuration
public class DiffServicesApplication {

    public static void main(String[] args) {
        JacgEnhancer.enhance();
        clearReport();
        ConfigurableApplicationContext app = SpringApplication.run(DiffServicesApplication.class, args);
        SpringUtil.setContext(app);
    }

    private static void clearReport() {
        try {
            ClassPool classPool = ClassPool.getDefault();
            ClassClassPath classPath = new ClassClassPath(SpringBootConfiguration.class);
            classPool.insertClassPath(classPath);
            CtClass ctClass = classPool.get("org.jacoco.cli.internal.report.internal.html.page.ReportPage");
            ctClass.defrost();
            CtMethod ctMethod = ctClass.getDeclaredMethod("footer");
            ctMethod.insertBefore("if (true) return;");
            ctClass.toClass();
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }
}
