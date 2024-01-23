package com.test.diff.services.params;

import lombok.*;

/**
 * @author wl
 */
@Getter
@Setter
public class ListProjectParams extends BaseParams{

    private int projectId;

    private String env;

    private String projectGroup;

    private String projectName;

    public static Builder builder(){
        return new Builder();
    };

    public static class Builder{

        private int page;
        private int size;
        private int projectId;
        private String env;
        private String projectGroup;
        private String projectName;

        public Builder page(int page){
            this.page = page;
            return this;
        }

        public Builder size(int size){
            this.size = size;
            return this;
        }

        public Builder projectId(int projectId){
            this.projectId = projectId;
            return this;
        }

        public Builder env(String env){
            this.env = env;
            return this;
        }

        public Builder projectGroup(String projectGroup){
            this.projectGroup = projectGroup;
            return this;
        }

        public Builder projectName(String projectName){
            this.projectName = projectName;
            return this;
        }

        public ListProjectParams build(){
            ListProjectParams params = new ListProjectParams();
            params.setPage(this.page);
            params.setSize(this.size);
            params.setProjectId(this.projectId);
            params.setEnv(this.env);
            params.setProjectGroup(this.projectGroup);
            params.setProjectName(this.projectName);
            return params;
        }

    }

}
