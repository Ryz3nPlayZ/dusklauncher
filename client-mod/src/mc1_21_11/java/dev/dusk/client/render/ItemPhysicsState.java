package dev.dusk.client.render;

/**
 * Whether Item Physics lays this dropped item down, decided when its render
 * state is extracted (see ItemPhysicsStateMixin) and read at submit time.
 */
public interface ItemPhysicsState {
    void duskclient$setResting(boolean resting);

    boolean duskclient$resting();
}
