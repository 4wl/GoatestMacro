package com.justingoat.goat.client.utils.animations;

import java.util.HashMap;
import java.util.Map;

public class AnimationController {
    private final Map<String, Animation> animations;
    private boolean isEnabled;

    public AnimationController() {
        this.animations = new HashMap<>();
        this.isEnabled = true;
    }

    public void update() {
        if (!isEnabled) return;
        animations.values().forEach(Animation::update);
    }

    public void animate(String key, float startValue, float endValue, long duration) {
        animate(key, startValue, endValue, duration, Easing::linear);
    }

    public void animate(String key, float startValue, float endValue, long duration, Animation.EasingFunction easingFunction) {
        Animation animation = new Animation(startValue, endValue, duration, easingFunction);
        animations.put(key, animation);
        animation.start();
    }

    public float getValue(String key) {
        Animation animation = animations.get(key);
        return animation != null ? animation.getCurrentValue() : 0.0f;
    }

    public float getValue(String key, float defaultValue) {
        Animation animation = animations.get(key);
        return animation != null ? animation.getCurrentValue() : defaultValue;
    }

    public void setEnabled(boolean enabled) {
        isEnabled = enabled;
    }
}
