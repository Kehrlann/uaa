package org.cloudfoundry.identity.uaa.db;

public class UaaDatabaseName {
    private static final String UAA_DB_NAME = "uaa";

    private final String gradleWorkerId;

    public UaaDatabaseName(String gradleWorkerId) {
        this.gradleWorkerId = gradleWorkerId;
    }

    public String getName() {
        if (gradleWorkerId == null) {
            return UAA_DB_NAME;
        }

        var boundedWorkerId = (Integer.parseInt(gradleWorkerId) % 24) + 1;
        return UAA_DB_NAME + "_" + boundedWorkerId;
    }
}
