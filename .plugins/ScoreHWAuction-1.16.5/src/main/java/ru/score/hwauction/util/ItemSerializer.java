package ru.score.hwauction.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

public final class ItemSerializer {

    private ItemSerializer() {}

    public static String serialize(ItemStack item) {
        if (item == null) throw new IllegalArgumentException("Cannot serialize null item");
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BukkitObjectOutputStream boos = new BukkitObjectOutputStream(baos)) {
            boos.writeObject(item);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize ItemStack: " + item.getType(), e);
        }
    }

    public static ItemStack deserialize(String data) {
        if (data == null || data.isBlank()) throw new IllegalArgumentException("Cannot deserialize empty data");
        try {
            byte[] bytes = Base64.getDecoder().decode(data);
            try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
                 BukkitObjectInputStream bois = new BukkitObjectInputStream(bais)) {
                Object obj = bois.readObject();
                if (!(obj instanceof ItemStack)) throw new RuntimeException("Deserialized object is not an ItemStack");
                return (ItemStack) obj;
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize ItemStack from data", e);
        }
    }
}
