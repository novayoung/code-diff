package com.test.diff.services.params;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ReportImParams implements Serializable {

    private static final long serialVersionUID = 1L;

    /******************************
     * service
     *****************************/

    private String env;

    private String group;

    private String service;

    /******************************
     * source
     *****************************/

    private String gitUrl;

    /******************************
     * dump & class
     *****************************/

    private List<ReportImAppParam> apps;


    /******************************
     * report
     *****************************/

    private Integer full;

    private String baseBranch;

    private String featureBranch;

    private String classBranch;


}
