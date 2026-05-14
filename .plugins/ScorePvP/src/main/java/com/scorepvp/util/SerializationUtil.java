package com.scorepvp.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

public final class SerializationUtil {

    private SerializationUtil() {}

    /**
     * Serializes an ItemStack array to a Base64 string for database storage.
     */
    public static String itemsToBase64(ItemStack[] items) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream)) {

            dataOutput.writeInt(items.length);
            for (ItemStack item : items) {
                dataOutput.writeObject(item);
            }
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());

        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize ItemStack array", e);
        }
    }

    /**
     * Deserializes a Base64 string back to an ItemStack array.
     */
    public static ItemStack[] itemsFromBase64(String data) {
        if (data == null || data.isEmpty()) return new ItemStack[0];
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
             BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream)) {

            int size = dataInput.readInt();
            ItemStack[] items = new ItemStack[size];
            for (int i = 0; i < size; i++) {
                items[i] = (ItemStack) dataInput.readObject();
            }
            return items;

        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize ItemStack array", e);
        }
    }

    /**
     * Serializes a single ItemStack.
     */
    public static String itemToBase64(ItemStack item) {
        return itemsToBase64(new ItemStack[]{item});
    }

    /**
     * Deserializes a single ItemStack.
     */
    public static ItemStack itemFromBase64(String data) {
        ItemStack[] items = itemsFromBase64(data);
        return (items.length > 0) ? items[0] : null;
    }
}
