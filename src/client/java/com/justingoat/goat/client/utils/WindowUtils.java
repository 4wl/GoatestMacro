package com.justingoat.goat.client.utils;

import org.lwjgl.BufferUtils;

import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;

public class WindowUtils {

    public static class WindowState {
        int width, height;
        int x, y;
        boolean decorated, floating, resizable;

        public WindowState(long handle) {
            IntBuffer widthBuf = BufferUtils.createIntBuffer(1);
            IntBuffer heightBuf = BufferUtils.createIntBuffer(1);
            glfwGetWindowSize(handle, widthBuf, heightBuf);
            this.width = widthBuf.get(0);
            this.height = heightBuf.get(0);

            IntBuffer xBuf = BufferUtils.createIntBuffer(1);
            IntBuffer yBuf = BufferUtils.createIntBuffer(1);
            glfwGetWindowPos(handle, xBuf, yBuf);
            this.x = xBuf.get(0);
            this.y = yBuf.get(0);

            this.decorated = glfwGetWindowAttrib(handle, GLFW_DECORATED) == GLFW_TRUE;
            this.floating = glfwGetWindowAttrib(handle, GLFW_FLOATING) == GLFW_TRUE;
            this.resizable = glfwGetWindowAttrib(handle, GLFW_RESIZABLE) == GLFW_TRUE;
        }

        public void restore(long handle) {
            glfwSetWindowSize(handle, width, height);
            glfwSetWindowPos(handle, x, y);
            glfwSetWindowAttrib(handle, GLFW_DECORATED, decorated ? GLFW_TRUE : GLFW_FALSE);
            glfwSetWindowAttrib(handle, GLFW_FLOATING, floating ? GLFW_TRUE : GLFW_FALSE);
            glfwSetWindowAttrib(handle, GLFW_RESIZABLE, resizable ? GLFW_TRUE : GLFW_FALSE);
        }
    }
}
