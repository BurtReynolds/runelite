package com.custom.events;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.*;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Plugin that monitors and broadcasts game events for external access
 */
@Slf4j
@PluginDescriptor(
    name = "Event Monitor",
    description = "Monitors and broadcasts game events for external access",
    tags = {"api", "events", "external"}
)
public class EventMonitorPlugin extends Plugin {

    private final List<GameEventListener> listeners = new CopyOnWriteArrayList<>();
    private final List<GameEvent> recentEvents = new CopyOnWriteArrayList<>();
    private static final int MAX_RECENT_EVENTS = 1000;

    @Override
    protected void startUp() throws Exception {
        log.info("Event Monitor Plugin started");
    }

    @Override
    protected void shutDown() throws Exception {
        log.info("Event Monitor Plugin stopped");
        listeners.clear();
        recentEvents.clear();
    }

    // Public API for managing listeners
    public void addEventListener(GameEventListener listener) {
        listeners.add(listener);
        log.debug("Added event listener");
    }

    public void removeEventListener(GameEventListener listener) {
        listeners.remove(listener);
        log.debug("Removed event listener");
    }

    public List<GameEvent> getRecentEvents() {
        return new ArrayList<>(recentEvents);
    }

    public List<GameEvent> getRecentEvents(int limit) {
        List<GameEvent> events = new ArrayList<>(recentEvents);
        if (events.size() > limit) {
            return events.subList(events.size() - limit, events.size());
        }
        return events;
    }

    // Private method to broadcast events
    private void broadcastEvent(GameEvent event) {
        // Add to recent events
        recentEvents.add(event);
        if (recentEvents.size() > MAX_RECENT_EVENTS) {
            recentEvents.remove(0);
        }

        // Notify all listeners
        for (GameEventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                log.error("Error notifying listener", e);
            }
        }
    }

    // Event subscriptions
    @Subscribe
    public void onGameTick(GameTick event) {
        broadcastEvent(new GameEvent("game_tick", System.currentTimeMillis(), null));
    }

    @Subscribe
    public void onStatChanged(StatChanged event) {
        GameEventData data = new GameEventData();
        data.put("skill", event.getSkill().getName());
        data.put("level", event.getLevel());
        data.put("boosted_level", event.getBoostedLevel());
        data.put("xp", event.getXp());

        broadcastEvent(new GameEvent("stat_changed", System.currentTimeMillis(), data));
    }

    @Subscribe
    public void onChatMessage(ChatMessage event) {
        GameEventData data = new GameEventData();
        data.put("type", event.getType().name());
        data.put("message", event.getMessage());
        data.put("sender", event.getName());
        data.put("timestamp", event.getTimestamp());

        broadcastEvent(new GameEvent("chat_message", System.currentTimeMillis(), data));
    }

    @Subscribe
    public void onHitsplatApplied(HitsplatApplied event) {
        GameEventData data = new GameEventData();
        String actorName = event.getActor().getName();
        data.put("actor", actorName != null ? actorName : "Unknown");
        data.put("hitsplat_type", event.getHitsplat().getHitsplatType().name());
        data.put("amount", event.getHitsplat().getAmount());

        broadcastEvent(new GameEvent("combat_hitsplat", System.currentTimeMillis(), data));
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        GameEventData data = new GameEventData();
        data.put("container_id", event.getContainerId());

        broadcastEvent(new GameEvent("inventory_changed", System.currentTimeMillis(), data));
    }

    @Subscribe
    public void onNpcSpawned(NpcSpawned event) {
        GameEventData data = new GameEventData();
        data.put("npc_id", event.getNpc().getId());
        data.put("npc_name", event.getNpc().getName());
        data.put("npc_index", event.getNpc().getIndex());

        broadcastEvent(new GameEvent("npc_spawned", System.currentTimeMillis(), data));
    }

    @Subscribe
    public void onNpcDespawned(NpcDespawned event) {
        GameEventData data = new GameEventData();
        data.put("npc_id", event.getNpc().getId());
        data.put("npc_name", event.getNpc().getName());

        broadcastEvent(new GameEvent("npc_despawned", System.currentTimeMillis(), data));
    }

    @Subscribe
    public void onAnimationChanged(AnimationChanged event) {
        GameEventData data = new GameEventData();
        String actorName = event.getActor().getName();
        data.put("actor", actorName != null ? actorName : "Unknown");
        data.put("animation_id", event.getActor().getAnimation());

        broadcastEvent(new GameEvent("animation_changed", System.currentTimeMillis(), data));
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        GameEventData data = new GameEventData();
        data.put("game_state", event.getGameState().name());

        broadcastEvent(new GameEvent("game_state_changed", System.currentTimeMillis(), data));
    }

    @Subscribe
    public void onActorDeath(ActorDeath event) {
        GameEventData data = new GameEventData();
        String actorName = event.getActor().getName();
        data.put("actor", actorName != null ? actorName : "Unknown");

        broadcastEvent(new GameEvent("actor_death", System.currentTimeMillis(), data));
    }

    @Subscribe
    public void onPlayerSpawned(PlayerSpawned event) {
        GameEventData data = new GameEventData();
        data.put("player_name", event.getPlayer().getName());

        broadcastEvent(new GameEvent("player_spawned", System.currentTimeMillis(), data));
    }

    @Subscribe
    public void onPlayerDespawned(PlayerDespawned event) {
        GameEventData data = new GameEventData();
        data.put("player_name", event.getPlayer().getName());

        broadcastEvent(new GameEvent("player_despawned", System.currentTimeMillis(), data));
    }
}
