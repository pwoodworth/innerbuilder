package org.jetbrains.plugins.innerbuilder;

public enum InnerBuilderOption {

    FINAL_SETTERS("finalSetters"),
    NEW_BUILDER_METHOD("newBuilderMethod"),
    COPY_CONSTRUCTOR("copyConstructor"),
    WITH_NOTATION("withNotation", true),
    JSR305_ANNOTATIONS("useJSR305Annotations"),
    FINDBUGS_ANNOTATION("useFindbugsAnnotation"),
    WITH_JAVADOC("withJavadoc"),
    FIELD_NAMES("fieldNames");

    private final String property;
    private final boolean textfield;

    InnerBuilderOption(String property) {
        this(property, false);
    }

    InnerBuilderOption(String property, boolean textfield) {
        this.property = String.format("GenerateInnerBuilder.%s", property);
        this.textfield = textfield;
    }

    public String getProperty() {
        return property;
    }

    public boolean isTextfield() {
        return textfield;
    }
}
