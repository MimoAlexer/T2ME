package dev.t2me;

public enum JobState {
    RUNNING,
    PAUSED,
    COMPLETED,
    CANCELLED,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED || this == FAILED;
    }
}
