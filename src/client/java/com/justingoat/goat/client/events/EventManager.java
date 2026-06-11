package com.justingoat.goat.client.events;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class EventManager {
    public static final EventManager INSTANCE = new EventManager();
    private static final Logger LOGGER = LoggerFactory.getLogger("goat-events");

    private final ConcurrentHashMap<Class<? extends AbstractEvent>, List<EventHandler>> eventHandlers;

    public EventManager() {
        eventHandlers = new ConcurrentHashMap<>();
    }

    public void register(Object listener) {
        try {
            Class<?> clazz = listener.getClass();
            while (clazz != null && clazz != Object.class) {
                for (Method method : clazz.getDeclaredMethods()) {
                    registerMethodIfEventListener(listener, method);
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Exception e) {
            LOGGER.error("Failed to register event listener: {}", listener.getClass().getSimpleName(), e);
        }
    }

    public void registerEvents(Object... listeners) {
        for (Object listener : listeners) {
            register(listener);
        }
    }

    public void unregister(Object listener) {
        try {
            for (List<EventHandler> handlers : eventHandlers.values()) {
                handlers.removeIf(handler -> handler.instance == listener);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to unregister event listener: {}", listener.getClass().getSimpleName(), e);
        }
    }

    public void fire(AbstractEvent event) {
        List<EventHandler> handlers = eventHandlers.get(event.getClass());
        if (handlers == null || handlers.isEmpty()) return;

        event.setCancelled(false);

        for (EventHandler handler : new ArrayList<>(handlers)) {
            try {
                handler.method.invoke(handler.instance, event);
                if (event.isCancelled()) break;
            } catch (Exception e) {
                LOGGER.error("Failed to invoke event handler: {}.{}",
                    handler.instance.getClass().getSimpleName(), handler.method.getName(), e);
            }
        }
    }

    public boolean isRegistered(Object listener) {
        for (List<EventHandler> handlers : eventHandlers.values()) {
            if (handlers.stream().anyMatch(handler -> handler.instance == listener)) return true;
        }
        return false;
    }

    public int getHandlerCount(Class<? extends AbstractEvent> eventType) {
        List<EventHandler> handlers = eventHandlers.get(eventType);
        return handlers == null ? 0 : handlers.size();
    }

    public boolean hasHandlers(Class<? extends AbstractEvent> eventType) {
        List<EventHandler> handlers = eventHandlers.get(eventType);
        return handlers != null && !handlers.isEmpty();
    }

    public void clearAll() {
        eventHandlers.clear();
    }

    public Set<Class<? extends AbstractEvent>> getRegisteredEventTypes() {
        return eventHandlers.keySet();
    }

    private void registerMethodIfEventListener(Object listener, Method method) {
        if (method.isAnnotationPresent(EventListener.class)) {
            EventListener annotation = method.getAnnotation(EventListener.class);
            Class<?>[] parameterTypes = method.getParameterTypes();

            if (parameterTypes.length == 1 && AbstractEvent.class.isAssignableFrom(parameterTypes[0])) {
                @SuppressWarnings("unchecked")
                Class<? extends AbstractEvent> eventType = (Class<? extends AbstractEvent>) parameterTypes[0];
                method.setAccessible(true);

                EventHandler handler = new EventHandler(listener, method, annotation.priority());
                eventHandlers.computeIfAbsent(eventType, k -> new ArrayList<>()).add(handler);
                eventHandlers.get(eventType).sort(Comparator.comparingInt(h -> h.priority.ordinal()));
            } else {
                LOGGER.warn("Invalid @EventListener method signature in {}: {}",
                    listener.getClass().getSimpleName(), method.getName());
            }
        }
    }

    private static class EventHandler {
        final Object instance;
        final Method method;
        final EventPriority priority;

        EventHandler(Object instance, Method method, EventPriority priority) {
            this.instance = instance;
            this.method = method;
            this.priority = priority;
        }
    }
}
