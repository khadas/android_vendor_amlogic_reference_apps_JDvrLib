package com.droidlogic.jdvrlib;

public class JDvrPlayerSettings {
    private JDvrPlayerSettings() {

    }
    public static Builder builder() {
        return new Builder();
    }
    public static final class Builder {
        public JDvrPlayerSettings build() {
            return new JDvrPlayerSettings();
        }
    }
}
