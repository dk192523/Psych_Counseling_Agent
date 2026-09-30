package com.dk.dkaiagent.rag;

/** Stable retrieval domains shared by knowledge sources. */
public enum KnowledgeDomain {
    CASES("psych-cases"),
    KNOWLEDGE("psych-knowledge"),
    POLICY("psych-policy"),
    EXAMS("psych-exams"),
    PROMPTS("psych-prompts");

    private final String value;

    KnowledgeDomain(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
