package com.taskscheduler.model;

public enum TaskPriority {

    HIGH(1),
    MEDIUM(5),
    LOW(10);

    private final int value;

    TaskPriority(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static TaskPriority fromValue(int value) {
        for (TaskPriority p : values()) {
            if (p.value == value) {
                return p;
            }
        }
        return MEDIUM;
    }
}
