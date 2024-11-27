package net.sneakymouse.sneakyvaults.utlitiy;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class InventoryUtility {
    /**
     * Convert an inventory into a B64 String.
     * (Note: This will only convert the inventory contents, not types or names)
     * @param inventory Inventory to convert
     * @return B64 Encoded inventory string
     * */
    public static String convertInventory(Inventory inventory) {

        try {
            //Save this string to file.
            return inventoryToBase64(inventory);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Get the ItemStack[] from the B64 String
     * @param data B64 string to decode into an ItemStack[]
     * @return Itemstack[] containing all saved items
     * */
    public static ItemStack[] getSavedInventory(String data) {

        try {
            return inventoryFromBase64(data);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return new ItemStack[0];
    }


    /**
     * Convert an inventory into a B64 String.
     * This method uses Paper's Serialization
     * (Note: This will only convert the inventory contents, not types or names)
     * @param inventory Inventory to convert
     * @return B64 Encoded inventory string
     * */
    public static List<String> inventoryPaperToBase64(Inventory inventory) {
        List<String> result = new ArrayList<>();

        for(ItemStack itemStack : inventory.getContents()) {
            if(itemStack == null) {
                result.add("");
            }
            else {
                result.add(Base64.getEncoder().encodeToString(itemStack.serializeAsBytes()));
            }
        }

        return result;
    }

    /**
     * Get the Inventory from the B64 String (Will have a generic name)
     * This method uses the Paper byte serialization
     * @param data B64 string to decode into an ItemStack[]
     * @return Inventory created from the encoded data
     * */
    public static List<ItemStack> inventoryPaperFromBase64(List<String> data) {

        List<ItemStack> result = new ArrayList<>();

        for(String s : data) {
            ItemStack itemStack;
            if(s.isEmpty()){
                itemStack = new ItemStack(Material.AIR);
            } else {
                itemStack = ItemStack.deserializeBytes(Base64Coder.decode(s));
            }
            result.add(itemStack);
        }

        return result;
    }


    /**
     * Convert an inventory into a B64 String.
     * (Note: This will only convert the inventory contents, not types or names)
     * @param inventory Inventory to convert
     * @return B64 Encoded inventory string
     * */
    public static String inventoryToBase64(Inventory inventory) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);

            dataOutput.writeInt(inventory.getSize());

            for (int i = 0; i < inventory.getSize(); i++) {
                dataOutput.writeObject(inventory.getItem(i));
            }

            dataOutput.close();
            return Base64Coder.encodeLines(outputStream.toByteArray());

            //Converts the inventory and its contents to base64, This also saves item meta-data and inventory type
        } catch (Exception e) {
            throw new IllegalStateException("Could not convert inventory to base64.", e);
        }
    }

    /**
     * Convert an inventory into a B64 String.
     * (Note: This will only convert the inventory contents, not types or names)
     * @param size Size of the inventory
     * @param items Item Contents
     * @return B64 Encoded inventory string
     * */
    public static String inventoryToBase64(int size, ItemStack[] items) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);

            dataOutput.writeInt(size);

            for (int i = 0; i < size; i++) {
                dataOutput.writeObject(items[i]);
            }

            dataOutput.close();
            return Base64Coder.encodeLines(outputStream.toByteArray());

            //Converts the inventory and its contents to base64, This also saves item meta-data and inventory type
        } catch (Exception e) {
            throw new IllegalStateException("Could not convert inventory to base64.", e);
        }
    }

    /**
     * Get the Inventory from the B64 String (Will have a generic name)
     * @param data B64 string to decode into an ItemStack[]
     * @return Inventory created from the encoded data
     * */
    public static ItemStack[] inventoryFromBase64(String data) {
        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64Coder.decodeLines(data));
            BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);
            int size = dataInput.readInt();
            ItemStack[] items = new ItemStack[size];

            for (int i = 0; i < size; i++) {
                items[i] = (ItemStack) dataInput.readObject();
            }

            dataInput.close();
            return items;
        } catch (ClassNotFoundException | IOException e) {
            e.printStackTrace();
        }
        return null;
    }

}
