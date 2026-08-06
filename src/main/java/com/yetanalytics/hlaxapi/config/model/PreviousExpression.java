package com.yetanalytics.hlaxapi.config.model;

/** Reads an object attribute value from the cache before the current reflection. */
public final class PreviousExpression implements Expression {

    public final Target target;

    public PreviousExpression(Target target) {
        this.target = target;
    }

    @Override
    public String toString() {
        return "Previous(" + (target == null ? "null" : target.toString()) + ")";
    }
}
