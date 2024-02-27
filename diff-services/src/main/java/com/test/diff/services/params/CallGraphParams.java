package com.test.diff.services.params;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Set;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CallGraphParams implements Serializable {

    private static final long serialVersionUID = 1L;

    private String env;

    private String group;

    private String service;

    private String host;

    private Set<String> caller;

    private Set<String> callee;

}
