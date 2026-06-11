package com.justingoat.goat.client.utils.animations;

public class Easing {

    public static float linear(float t) {
        return t;
    }

    public static float easeIn(float t) {
        return t * t;
    }

    public static float easeOut(float t) {
        return 1f - (1f - t) * (1f - t);
    }

    public static float easeInOut(float t) {
        if (t < 0.5f) {
            return 2f * t * t;
        } else {
            return 1f - (-2f * t * t + 4f * t - 1f);
        }
    }

    public static float easeOutCubic(float t) {
        return 1f - (float) Math.pow(1f - t, 3f);
    }

    public static float bounce(float t) {
        if (t < 0.3636f) {
            return 7.5625f * t * t;
        } else if (t < 0.7273f) {
            t -= 0.5455f;
            return 7.5625f * t * t + 0.75f;
        } else if (t < 0.9091f) {
            t -= 0.8182f;
            return 7.5625f * t * t + 0.9375f;
        } else {
            t -= 0.9545f;
            return 7.5625f * t * t + 0.9844f;
        }
    }

    public static float elastic(float t) {
        if (t == 0f || t == 1f) return t;
        float p = 0.3f;
        float s = p / 4f;
        return (float) (Math.pow(2f, -10f * t) * Math.sin((t - s) * (2f * Math.PI) / p) + 1f);
    }
}
