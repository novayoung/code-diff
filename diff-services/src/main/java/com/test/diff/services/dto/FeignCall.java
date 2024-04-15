package com.test.diff.services.dto;

import lombok.Data;

@Data
public class FeignCall {

    private String service;

    private String feign;

    private String method;

    private String uri;

}
