package com.justingoat.goat.client.utils.animations;

public class Animation {
    private float startValue;
    private float endValue;
    private float currentValue;
    private long duration;
    private long startTime;
    private boolean isRunning;
    private boolean isComplete;
    private EasingFunction easingFunction;

    @FunctionalInterface
    public interface EasingFunction {
        float apply(float t);
    }

    public Animation(float startValue, float endValue, long duration, EasingFunction easingFunction) {
        this.startValue = startValue;
        this.endValue = endValue;
        this.currentValue = startValue;
        this.duration = duration;
        this.easingFunction = easingFunction != null ? easingFunction : Easing::linear;
        this.isRunning = false;
        this.isComplete = false;
    }

    public void start() {
        this.startTime = System.currentTimeMillis();
        this.isRunning = true;
        this.isComplete = false;
        this.currentValue = startValue;
    }

    public void update() {
        if (!isRunning || isComplete) return;

        long elapsed = System.currentTimeMillis() - startTime;
        float progress = Math.min((float) elapsed / duration, 1.0f);
        float easedProgress = easingFunction.apply(progress);
        currentValue = startValue + (endValue - startValue) * easedProgress;

        if (progress >= 1.0f) {
            currentValue = endValue;
            isComplete = true;
            isRunning = false;
        }
    }

    public void stop() {
        isRunning = false;
        isComplete = true;
        currentValue = endValue;
    }

    public void reset() {
        isRunning = false;
        isComplete = false;
        currentValue = startValue;
    }

    public float getCurrentValue() { return currentValue; }
    public boolean isRunning() { return isRunning; }
    public boolean isComplete() { return isComplete; }
}
