package io.github.valossa515.jobmanager.application.job.create;

public final class JobTypeAdmission {
    private static final String SUPPORTED_TYPE = "NO_OP";

    private JobTypeAdmission() {
    }

    public static void requireSupported(String type) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException(
                    "Job type must not be blank"
            );
        }

        if (!SUPPORTED_TYPE.equals(type)) {
            throw new UnsupportedJobTypeException();
        }
    }

    public static class UnsupportedJobTypeException
            extends IllegalStateException {

        public UnsupportedJobTypeException() {
            super(
                    "Unsupported job type. Only NO_OP is currently supported"
            );
        }
    }
}