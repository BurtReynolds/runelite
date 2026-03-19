package net.runelite.client.plugins.objectdetection;

/**
 * Interface for listening to object spawn/despawn events
 */
public interface ObjectEventListener {
    /**
     * Called when a new object is detected in the scene
     * @param object The newly spawned object
     */
    void onObjectSpawned(GameObjectInfo object);

    /**
     * Called when an object is removed from the scene
     * @param objectKey The key of the despawned object (format: {id}_{x}_{y}_{plane})
     */
    void onObjectDespawned(String objectKey);

    /**
     * Called when an existing object's name changes (e.g. trap state change)
     * @param object The object with its new name/actions
     * @param oldName The previous name
     */
    void onObjectStateChanged(GameObjectInfo object, String oldName);
}
