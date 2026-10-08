package com.example.bedfight.util;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Sends 1.8 title packets through reflection (falls back to chat). */
public final class Titles {
    private static boolean initialised;
    private static boolean working;
    private static Constructor<?> timesCtor;
    private static Constructor<?> textCtor;
    private static Object titleAction;
    private static Object subtitleAction;
    private static Method serializer;
    private static Method getHandle;
    private static Field connection;
    private static Method sendPacket;

    private Titles() {}

    private static void init() {
        if (initialised) {
            return;
        }
        initialised = true;
        try {
            String pkg = Bukkit.getServer().getClass().getPackage().getName();
            String ver = pkg.substring(pkg.lastIndexOf('.') + 1);
            String nms = "net.minecraft.server." + ver + ".";
            Class<?> packet = Class.forName(nms + "PacketPlayOutTitle");
            Class<?> action = Class.forName(nms + "PacketPlayOutTitle$EnumTitleAction");
            Class<?> chat = Class.forName(nms + "IChatBaseComponent");
            Class<?> chatSerializer = Class.forName(nms + "IChatBaseComponent$ChatSerializer");
            Class<?> basePacket = Class.forName(nms + "Packet");
            timesCtor = packet.getConstructor(int.class, int.class, int.class);
            textCtor = packet.getConstructor(action, chat);
            titleAction = constant(action, "TITLE");
            subtitleAction = constant(action, "SUBTITLE");
            serializer = chatSerializer.getMethod("a", String.class);
            getHandle = Class.forName("org.bukkit.craftbukkit." + ver + ".entity.CraftPlayer").getMethod("getHandle");
            connection = Class.forName(nms + "EntityPlayer").getField("playerConnection");
            sendPacket = Class.forName(nms + "PlayerConnection").getMethod("sendPacket", basePacket);
            working = titleAction != null && subtitleAction != null;
        } catch (Exception ex) {
            Bukkit.getLogger().warning("[BedFight] Title packets unavailable, using chat fallback: " + ex);
            working = false;
        }
    }

    private static Object constant(Class<?> enumClass, String name) {
        for (Object o : enumClass.getEnumConstants()) {
            if (((Enum<?>) o).name().equals(name)) {
                return o;
            }
        }
        return null;
    }

    private static Object component(String text) throws Exception {
        String json = "{\"text\":\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        return serializer.invoke(null, json);
    }

    public static void send(Player p, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        init();
        if (working) {
            try {
                Object conn = connection.get(getHandle.invoke(p));
                sendPacket.invoke(conn, timesCtor.newInstance(fadeIn, stay, fadeOut));
                sendPacket.invoke(conn, textCtor.newInstance(subtitleAction, component(subtitle == null ? "" : subtitle)));
                sendPacket.invoke(conn, textCtor.newInstance(titleAction, component(title == null ? "" : title)));
                return;
            } catch (Exception ex) {
                working = false;
                Bukkit.getLogger().warning("[BedFight] Title sending failed, using chat fallback: " + ex);
            }
        }
        p.sendMessage(title + (subtitle == null || subtitle.isEmpty() ? "" : " " + subtitle));
    }
}
