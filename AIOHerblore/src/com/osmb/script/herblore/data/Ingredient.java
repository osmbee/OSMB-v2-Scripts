package com.osmb.script.herblore.data;

public class Ingredient {

    private final int itemID;
    private final int amount;

    public Ingredient(int itemID, int amount) {
        this.itemID = itemID;
        this.amount = amount;
    }

    public Ingredient(int itemID) {
        this.itemID = itemID;
        this.amount = 1;
    }

    public int getAmount() {
        return amount;
    }

    public int getItemID() {
        return itemID;
    }
}
