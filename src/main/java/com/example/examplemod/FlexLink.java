package com.eniac.flexlink;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import org.java_websocket.server.WebSocketServer;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.WebSocket;
import java.net.InetSocketAddress;

import net.minecraft.client.player.LocalPlayer;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.concurrent.CompletableFuture;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import com.mohistmc.itemexport.utils.ItemToBase64Helper;

// The value here should match an entry in the META-INF/mods.toml file
@Mod(FlexLink.MODID)
public class FlexLink
{
    // Define mod id in a common place for everything to reference
    public static final String MODID = "flexlink";
    // Directly reference a slf4j logger
    private static final Logger LOGGER = LogUtils.getLogger();

    private static ModWebSocketServer webSocketServer;
    private static ExecutorService webSocketExecutor = Executors.newSingleThreadExecutor();

    private static Map<WebSocket, String> clientConnections = new ConcurrentHashMap<>(); // Use ConcurrentHashMap for thread safety
    private static ItemStack[] previousInventory = new ItemStack[36]; // Default 36-slot inventory

    public static int tickCounter = 0; // Counter for ticks
    public static int lastSlot = -1; // Last slot clicked
    public static int lastPlayerSlot = -1; // Last slot clicked

    public FlexLink()
    {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        // Register ourselves for server and other game events we are interested in
        MinecraftForge.EVENT_BUS.register(this);

        // Register our mod's ForgeConfigSpec
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // Initialize previousInventory with empty ItemStacks
        for (int i = 0; i < previousInventory.length; i++) {
            previousInventory[i] = ItemStack.EMPTY;
        }

        // Start the WebSocket server
        int port = 28887; // Choose a port
        webSocketServer = new ModWebSocketServer(new InetSocketAddress(port));
        webSocketServer.start();
        LOGGER.debug("WebSocket server started on port: " + port);
    }

    private class ModWebSocketServer extends WebSocketServer {

        public ModWebSocketServer(InetSocketAddress address) {
            super(address);
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            LOGGER.debug("WebSocket connection opened: " + conn.getRemoteSocketAddress());

            // Add connection to clientConnections
            clientConnections.put(conn, conn.getRemoteSocketAddress().toString());

            // Send the entire inventory to the new client
            getPlayerInventoryJsonAsync().thenAccept(inventoryJson -> {
                conn.send(inventoryJson);
            });
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            LOGGER.debug("Received message from " + conn.getRemoteSocketAddress() + ": " + message);

            try {
                JsonObject json = new Gson().fromJson(message, JsonObject.class);
                if (json.has("slot")) {
                    int slot = json.get("slot").getAsInt();
                    handleSwapItem(slot);
                } else {
                    LOGGER.warn("Received message does not contain 'slot' field: " + message);
                }
            } catch (Exception e) {
                LOGGER.error("Error parsing message from client: " + message, e);
            }
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            LOGGER.debug("WebSocket connection closed: " + conn.getRemoteSocketAddress() + " Reason: " + reason);
            clientConnections.remove(conn);
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            LOGGER.error("WebSocket error: ", ex);
        }

        @Override
        public void onStart() {
            LOGGER.debug("WebSocket server started on port: " + getPort());
        }
    }

    private static void moveInventoryItem(int srcIdx, int dstIdx) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        NonNullList<ItemStack> a = p.getInventory().items;
        MultiPlayerGameMode gm = Minecraft.getInstance().gameMode;
        if (gm == null) return;
        // if (a.get(srcIdx).isEmpty()) return;
        if (srcIdx == dstIdx) return;

        if (a.get(dstIdx).isEmpty()) {
            gm.handleInventoryMouseClick(
                    /*containerId=*/0,
                    /*slotNum=*/srcIdx < 9 ? srcIdx + 36 : srcIdx,
                    /*buttonNum=*/0,
                    ClickType.PICKUP,
                    p);
            gm.handleInventoryMouseClick(
                    /*containerId=*/0,
                    /*slotNum=*/dstIdx < 9 ? dstIdx + 36 : dstIdx,
                    /*buttonNum=*/0,
                    ClickType.PICKUP,
                    p);
        } else {
            gm.handleInventoryMouseClick(
                    /*containerId=*/0,
                    /*slotNum=*/srcIdx < 9 ? srcIdx + 36 : srcIdx,
                    /*buttonNum=*/0,
                    ClickType.PICKUP,
                    p);
            gm.handleInventoryMouseClick(
                    /*containerId=*/0,
                    /*slotNum=*/dstIdx < 9 ? dstIdx + 36 : dstIdx,
                    /*buttonNum=*/0,
                    ClickType.PICKUP,
                    p);
            gm.handleInventoryMouseClick(
                    /*containerId=*/0,
                    /*slotNum=*/srcIdx < 9 ? srcIdx + 36 : srcIdx,
                    /*buttonNum=*/0,
                    ClickType.PICKUP,
                    p);
        }
    }

    private void handleSwapItem(int slot) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player != null) {
            AbstractContainerMenu container = player.containerMenu;
            int containerId = container.containerId;
            int currentPlayerSlot = player.getInventory().selected;
            
            Inventory inventory = player.getInventory();
            ItemStack slotItem = inventory.getItem(slot);
            ItemStack mainHandItem = player.getMainHandItem();

            if (slotItem.isEmpty() && mainHandItem.isEmpty()) {
                LOGGER.warn("Both slot and main hand are empty, nothing to swap.");
                return;
            }

            LOGGER.warn("Slot: " + slot + " Current player slot: " + currentPlayerSlot + " Last slot: " + lastSlot + " Last player slot: " + lastPlayerSlot);
            if ((lastSlot >= 0) && (lastPlayerSlot == currentPlayerSlot) && (lastSlot != slot) && (currentPlayerSlot != slot)) {
                // Swap items between lastSlot and slot
                moveInventoryItem(currentPlayerSlot, lastSlot);
            }
            lastSlot = slot;
            lastPlayerSlot = currentPlayerSlot;

            moveInventoryItem(slot, currentPlayerSlot);

            int hotbarSlotIndex = currentPlayerSlot;

            LOGGER.warn("Hotbar slot: " + hotbarSlotIndex + " slot: " + slot + " Item name: " + player.getInventory().getItem(slot).getItem().getDescriptionId());
    
        } else {
            LOGGER.warn("Player is null, cannot perform item swap.");
        }
    }
    
    // Method to get the player's entire inventory as JSON asynchronously
    private CompletableFuture<String> getPlayerInventoryJsonAsync() {
        CompletableFuture<String> future = new CompletableFuture<>();

        // Schedule the task on the main thread
        Minecraft.getInstance().execute(() -> {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
                Inventory inventory = player.getInventory();
                JsonArray jsonArray = new JsonArray();

                for (int i = 0; i < inventory.items.size(); i++) {
                    ItemStack stack = inventory.items.get(i);
                    JsonObject itemJson = new JsonObject();
                    itemJson.addProperty("slot", i);

                    if (!stack.isEmpty()) {
                        itemJson.addProperty("name", stack.getItem().getDescriptionId());
                        itemJson.addProperty("count", stack.getCount());
                        itemJson.addProperty("base64", new ItemToBase64Helper(stack).toBase64());
                    } else {
                        itemJson.addProperty("name", "");
                        itemJson.addProperty("count", 0);
                        itemJson.addProperty("base64", "");
                    }
                    jsonArray.add(itemJson);
                }

                JsonObject inventoryJson = new JsonObject();
                inventoryJson.add("items", jsonArray);

                future.complete(inventoryJson.toString());
            } else {
                future.complete("{}");
            }
        });

        return future;
    }

    // Helper method to serialize List<JsonObject> to JsonArray
    public static JsonArray serializeListToJsonArray(List<JsonObject> list) {
        JsonArray jsonArray = new JsonArray();
        for (JsonObject obj : list) {
            jsonArray.add(obj);
        }
        return jsonArray;
    }

    private void commonSetup(final FMLCommonSetupEvent event)
    {
        // Some common setup code
        LOGGER.info("HELLO FROM COMMON SETUP");

        if (Config.logDirtBlock)
            LOGGER.info("DIRT BLOCK >> {}", ForgeRegistries.BLOCKS.getKey(Blocks.DIRT));

        LOGGER.info(Config.magicNumberIntroduction + Config.magicNumber);

        Config.items.forEach((item) -> LOGGER.info("ITEM >> {}", item.toString()));
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event)
    {
        // Do something when the server starts
        LOGGER.info("HELLO from server starting");
    }

    // Event subscriber for mod lifecycle events (Mod Event Bus)
    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ModEventHandlers
    {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event)
        {
            // Some client setup code
            LOGGER.info("HELLO FROM CLIENT SETUP");
            LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
        }
    }

    // Event subscriber for game events (Forge Event Bus)
    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static class ForgeEventHandlers
    {
        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event)
        {
            if (event.phase != TickEvent.Phase.END) {
                return; // Only process at the end of the tick
            }

            tickCounter++;
            if (tickCounter >= 2) { // Approximately every 100ms (assuming 20 ticks per second)
                tickCounter = 0; // Reset the counter

                // LOGGER.info(MODID + " ClientTickEvent, tickCounter: " + tickCounter);

                // Now check the inventory
                LocalPlayer player = Minecraft.getInstance().player;
                if (player != null && player.getInventory() != null) {
                    Inventory inventory = player.getInventory();

                    List<JsonObject> items = new ArrayList<>();

                    for (int i = 0; i < inventory.items.size(); i++) {
                        ItemStack currentStack = inventory.items.get(i);
                        ItemStack previousStack = previousInventory[i];

                        if (!ItemStack.matches(currentStack, previousStack)) {
                            // Item has changed
                            previousInventory[i] = currentStack.copy(); // Update the previous inventory

                            JsonObject itemJson = new JsonObject();
                            itemJson.addProperty("slot", i);

                            if (!currentStack.isEmpty()) {
                                // Prepare JSON data for the changed item
                                itemJson.addProperty("name", currentStack.getItem().getDescriptionId());
                                itemJson.addProperty("count", currentStack.getCount());
                                itemJson.addProperty("base64", new ItemToBase64Helper(currentStack).toBase64());
                            } else {
                                // The item was removed from this slot
                                itemJson.addProperty("name", "");
                                itemJson.addProperty("count", 0);
                                itemJson.addProperty("base64", "");
                            }

                            items.add(itemJson);
                        }
                    }

                    if (!items.isEmpty()) {
                        // Send the changed items to WebSocket clients
                        JsonObject changesJson = new JsonObject();
                        changesJson.add("items", serializeListToJsonArray(items));
                        String jsonString = changesJson.toString();

                        // Send the JSON string to all clients
                        webSocketExecutor.execute(() -> {
                            for (WebSocket conn : clientConnections.keySet()) {
                                conn.send(jsonString);
                            }
                        });
                    }
                }
            }
        }
    }
}
