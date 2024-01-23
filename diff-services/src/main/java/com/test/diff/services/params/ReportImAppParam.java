package com.test.diff.services.params;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ReportImAppParam implements Serializable {

    private static final long serialVersionUID = 1L;

    private String name;

    private String includes;

    private String excludes;

    private String host;

    private String port;

}
