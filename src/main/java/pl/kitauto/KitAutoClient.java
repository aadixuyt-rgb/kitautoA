package pl.kitauto;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import org.lwjgl.glfw.GLFW;

/**
 * Klientowy mod Fabric 1.21.4.
 * Klawisz N włącza/wyłącza automatyzację: /kit -> klik Gray Shulker Box (slot 20) ->
 * czekanie na nowe GUI -> klik Lime Dye (slot 42) -> koniec cyklu.
 * Następny /kit dokładnie 63 sekundy (1260 ticków) od WYSŁANIA poprzedniego, nie od końca procedury.
 * Brak /kosz, brak obsługi ekwipunku - tylko ta jedna sekwencja.
 */
public class KitAutoClient implements ClientModInitializer {
    private static final String TAG = "[KitAuto]";
    private static final int INTERVAL_TICKS = 63 * 20; // 63 s przy 20 TPS = 1260 ticków

    private enum State { DISABLED, WAITING_FOR_NEXT_CYCLE, WAITING_FOR_KIT_GUI, WAITING_FOR_LIME_GUI, FINISHED }

    private State state = State.DISABLED;
    private KeyBinding toggleKey;

    private long tickCounter = 0;
    private long cycleStartTick = 0; // tick, w którym wysłano ostatnie /kit
    private int kitGuiSyncId = -1;   // syncId GUI otwartego po /kit (żeby odróżnić je od kolejnego GUI)

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.kitauto.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_N,
                "key.categories.kitauto"
        ));

        System.out.println(TAG + " Loaded");

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void onTick(MinecraftClient client) {
        tickCounter++;

        while (toggleKey.wasPressed()) {
            toggle(client);
        }

        if (state == State.DISABLED) return;

        // Bez gracza / bez połączenia z serwerem -> nic nie rób, nie crashuj.
        if (client.player == null || client.getNetworkHandler() == null || client.interactionManager == null) {
            return;
        }

        switch (state) {
            case WAITING_FOR_NEXT_CYCLE -> {
                if (tickCounter - cycleStartTick >= INTERVAL_TICKS) {
                    sendKit(client);
                }
            }
            case WAITING_FOR_KIT_GUI -> tryClickGrayShulker(client);
            case WAITING_FOR_LIME_GUI -> tryClickLimeDye(client);
            case FINISHED -> {
                // Procedura zakończona, timer i tak liczy od cycleStartTick (momentu wysłania /kit).
                state = State.WAITING_FOR_NEXT_CYCLE;
            }
            default -> { }
        }
    }

    private void toggle(MinecraftClient client) {
        if (state == State.DISABLED) {
            System.out.println(TAG + " Enabled");
            if (client.player != null && client.getNetworkHandler() != null) {
                sendKit(client);
            } else {
                // Gracz nie jest jeszcze w grze - poczekaj na najbliższy możliwy moment.
                state = State.WAITING_FOR_NEXT_CYCLE;
                cycleStartTick = tickCounter - INTERVAL_TICKS; // wymuś natychmiastową próbę na kolejnym ticku
            }
        } else {
            System.out.println(TAG + " Disabled");
            state = State.DISABLED;
            kitGuiSyncId = -1;
        }
    }

    private void sendKit(MinecraftClient client) {
        System.out.println(TAG + " Starting cycle");
        System.out.println(TAG + " Sending /kit");
        client.getNetworkHandler().sendChatCommand("kit");
        cycleStartTick = tickCounter; // timer liczony od WYSŁANIA /kit, nie od końca procedury
        kitGuiSyncId = -1;
        state = State.WAITING_FOR_KIT_GUI;
    }

    private void tryClickGrayShulker(MinecraftClient client) {
        if (!(client.currentScreen instanceof HandledScreen<?> handled)) return; // GUI jeszcze nie otwarte - czekaj
        ScreenHandler handler = handled.getScreenHandler();
        if (handler.slots.size() <= 20) return;

        ItemStack stack = handler.getSlot(20).getStack();
        if (stack.isEmpty() || stack.getItem() != Items.GRAY_SHULKER_BOX) return; // niewłaściwe/jeszcze puste GUI - czekaj

        System.out.println(TAG + " Found kit GUI");
        System.out.println(TAG + " Clicking gray shulker");
        client.interactionManager.clickSlot(handler.syncId, 20, 0, SlotActionType.PICKUP, client.player);

        kitGuiSyncId = handler.syncId;
        System.out.println(TAG + " Waiting for next GUI");
        state = State.WAITING_FOR_LIME_GUI;
    }

    private void tryClickLimeDye(MinecraftClient client) {
        if (!(client.currentScreen instanceof HandledScreen<?> handled)) return;
        ScreenHandler handler = handled.getScreenHandler();

        // To musi być NOWE GUI, a nie dalej to samo okno shulkera.
        if (handler.syncId == kitGuiSyncId) return;
        if (handler.slots.size() <= 42) return;

        ItemStack stack = handler.getSlot(42).getStack();
        if (stack.isEmpty() || stack.getItem() != Items.LIME_DYE) return; // GUI jeszcze się nie załadowało - czekaj

        System.out.println(TAG + " Found lime dye GUI");
        System.out.println(TAG + " Clicking lime dye");
        client.interactionManager.clickSlot(handler.syncId, 42, 0, SlotActionType.PICKUP, client.player);

        System.out.println(TAG + " Cycle procedure finished");
        state = State.FINISHED;
    }
}
