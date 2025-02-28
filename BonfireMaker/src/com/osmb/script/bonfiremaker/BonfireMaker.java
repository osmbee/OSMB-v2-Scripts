package com.osmb.script.bonfiremaker;

import com.osmb.api.item.ItemID;
import com.osmb.api.item.ItemSearchResult;
import com.osmb.api.location.position.types.LocalPosition;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.scene.RSObject;
import com.osmb.api.scene.RSTile;
import com.osmb.api.script.Script;
import com.osmb.api.script.ScriptDefinition;
import com.osmb.api.script.SkillCategory;
import com.osmb.api.shape.Polygon;
import com.osmb.api.ui.chatbox.dialogue.DialogueType;
import com.osmb.api.utils.UIResult;
import com.osmb.api.utils.UIResultList;
import com.osmb.api.utils.timing.Timer;
import com.osmb.api.visual.SearchablePixel;
import com.osmb.api.visual.color.ColorModel;
import com.osmb.api.visual.color.tolerance.impl.ChannelThresholdComparator;
import javafx.scene.Scene;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

@ScriptDefinition(name = "Bonfire Maker", description = "Makes bonfires and burns logs on them.", skillCategory = SkillCategory.FIREMAKING, version = 1.0, author = "Joe")
public class BonfireMaker extends Script {

    public static final String[] BANK_NAMES = {"Bank", "Chest", "Bank booth", "Bank chest", "Grand Exchange booth"};
    public static final String[] BANK_ACTIONS = {"bank", "open"};
    private static final int AMOUNT_CHANGE_TIMEOUT_SECONDS = 6;
    private int selectedLogsID = ItemID.LOGS;
    private WorldPosition bonfirePosition;
    private WorldPosition bonfireTargetCreationPos;
    private int logsBurnt;
    private int logsBurntOnFire;
    private String logName;
    private boolean forceNewPosition = false;

    public BonfireMaker(Object scriptCore) {
        super(scriptCore);
    }

    @Override
    public void onStart() {
        ScriptOptions ui = new ScriptOptions();
        Scene scene = ui.buildScene(this);
        getStageController().show(scene, "Options", false);
        selectedLogsID = ui.getSelectedLog();
        logName = getItemManager().getItemName(selectedLogsID);
        if (logName == null) {
            log(getClass().getSimpleName(), "Could not find name of selected logs...");
            stop();
        }
    }



    @Override
    public int poll() {
        if (getWidgetManager().getBank().isVisible()) {
            log(getClass().getSimpleName(), "Handling bank interface...");
            handleBank();
            return 0;
        }
        if (getWidgetManager().getInventory().isOpen()) {
            if (!getItemManager().unSelectItemIfSelected()) {
                log(getClass().getSimpleName(), "Failed to unselect item...");
            }
        }
        UIResultList<ItemSearchResult> logs = getItemManager().findAllOfItem(getWidgetManager().getInventory(), selectedLogsID);
        if (logs.isNotVisible()) {
            return 0;
        }
        if (logs.isEmpty()) {
            log(getClass().getSimpleName(), "Opening bank");
            openBank();
            return 0;
        }

        if (isDialogueVisible()) {
            log(getClass().getSimpleName(), "Handling dialogue");
            handleDialogue();
            return 0;
        }

        if (bonfirePosition == null) {
            log(getClass().getSimpleName(), "No bonfire active, we need to light a bonfire");
            // walk to target position, if one is valid
            if (bonfireTargetCreationPos != null) {
                log(getClass().getSimpleName(), "Running to target light position to escape a currently active bonfire...");
                WorldPosition myPos = getWorldPosition();
                if (!bonfireTargetCreationPos.equals(myPos)) {
                    getWalker().getSettings().setBreakDistance(0);
                    getWalker().getSettings().setTileRandomisationRadius(0);
                    getWalker().walkTo(bonfireTargetCreationPos);
                } else {
                    log(getClass().getSimpleName(), "Arrived at target position...");
                    bonfireTargetCreationPos = null;
                }
            } else if (forceNewPosition) {
                log(getClass().getSimpleName(), "Moving to new light position...");
                moveToNewPosition();
            } else {
                log(getClass().getSimpleName(), "Lighting bonfire...");
                lightBonfire(logs);
            }
        } else {
            log(getClass().getSimpleName(), "Bonfire active");
            burnLogsOnBonfire(logs);
        }

        return 0;
    }

    private void handleBank() {
        if (!getWidgetManager().getBank().depositAll(new int[]{ItemID.TINDERBOX, selectedLogsID})) {
            return;
        }
        Optional<Integer> freeSlots = getItemManager().getFreeSlotsInteger(getWidgetManager().getInventory());
        if (freeSlots.isEmpty()) {
            return;
        }
        if (freeSlots.get() == 0) {
            if (bonfirePosition == null) {
                forceNewPosition = true;
            }
            getWidgetManager().getBank().close();
            return;
        }
        UIResult<ItemSearchResult> logsBank = getItemManager().findItem(getWidgetManager().getBank(), selectedLogsID);
        if (logsBank.isNotVisible()) {
            return;
        }
        if (logsBank.isNotFound()) {
            log(getClass().getSimpleName(), "Ran out of logs, stopping script...");
            stop();
            return;
        }
        getWidgetManager().getBank().withdraw(selectedLogsID, Integer.MAX_VALUE);
    }

    private void openBank() {
        // Implement banking logic here
        log("Opening bank to withdraw logs...");
        log(getClass().getSimpleName(), "Searching for a bank...");
        // Find bank and open it
        List<RSObject> banksFound = getObjectManager().getObjects(gameObject -> {
            // if object has no name
            if (gameObject.getName() == null) {
                return false;
            }
            // has no interact options (eg. bank, open etc.)
            if (gameObject.getActions() == null) {
                return false;
            }

            if (!Arrays.stream(BANK_NAMES).anyMatch(name -> name.equalsIgnoreCase(gameObject.getName()))) {
                return false;
            }

            // if no actions contain bank or open
            if (!Arrays.stream(gameObject.getActions()).anyMatch(action -> Arrays.stream(BANK_ACTIONS).anyMatch(bankAction -> bankAction.equalsIgnoreCase(action)))) {
                return false;
            }
            // final check is if the object is reachable
            return gameObject.canReach();
        });
        //can't find a bank
        if (banksFound.isEmpty()) {
            log(getClass().getSimpleName(), "Can't find any banks matching criteria...");
            return;
        }
        RSObject object = (RSObject) getUtils().getClosest(banksFound);
        if (!object.interact(BANK_ACTIONS)) return;
        AtomicReference<Timer> positionChangeTimer = new AtomicReference<>(new Timer());
        AtomicReference<WorldPosition> pos = new AtomicReference<>(null);
        submitTask(() -> {
            WorldPosition position = getWorldPosition();
            if (position == null) {
                return false;
            }
            if (pos.get() == null || !position.equals(pos.get())) {
                positionChangeTimer.get().reset();
                pos.set(position);
            }

            return getWidgetManager().getBank().isVisible() || positionChangeTimer.get().timeElapsed() > 2000;
        }, 15000);
    }

    private boolean isDialogueVisible() {
        DialogueType dialogueType = getWidgetManager().getDialogue().getDialogueType();
        return dialogueType != null && dialogueType == DialogueType.ITEM_OPTION;
    }

    private void handleDialogue() {
        boolean result = getWidgetManager().getDialogue().selectItem(selectedLogsID);
        if (!result) {
            log(getClass().getSimpleName(), "Failed to select item in dialogue.");
        }
        // Sleep until finished burning
        waitUntilFinishedBurning(selectedLogsID);
    }

    private void lightBonfire(UIResultList<ItemSearchResult> logs) {
        Optional<ItemSearchResult> tinderbox = findTinderbox();
        if (!tinderbox.isPresent()) {
            log(getClass().getSimpleName(), "Tinderbox not found, stopping script...");
            stop();
            return;
        }

        if (!getItemManager().unSelectItemIfSelected()) {
            log(getClass().getSimpleName(), "Failed to unselect item.");
            return;
        }

        WorldPosition lightPosition = getWorldPosition();
        Optional<Integer> freeSlots = getItemManager().getTakenSlotsInteger(getWidgetManager().getInventory());
        if (!freeSlots.isPresent()) {
            log(getClass().getSimpleName(), "Failed to get free slots in inventory.");
            return;
        }

        if (!tinderbox.get().interact()) {
            return;
        }
        // select random log
        if (!logs.get(random(logs.size())).interact()) {
            return;
        }

        boolean lightingFire = submitTask(() -> {
            Optional<Integer> freeSlots2 = getItemManager().getTakenSlotsInteger(getWidgetManager().getInventory());
            return freeSlots2.isPresent() && freeSlots2.get() < freeSlots.get();
        }, 3500);

        // if we failed to light the fire, usually means we're in a spot you can't make fires
        if (!lightingFire) {
            forceNewPosition = true;
        } else {
            log(getClass().getSimpleName(), "Waiting for fire to light...");
            waitForFireToLight(lightPosition);
        }
    }

    private Optional<ItemSearchResult> findTinderbox() {
        UIResult<ItemSearchResult> tinderbox = getItemManager().findItem(getWidgetManager().getInventory(), ItemID.TINDERBOX);
        if (tinderbox.isNotVisible() || tinderbox.isNotFound()) {
            return Optional.empty();
        }
        return Optional.of(tinderbox.get());
    }

    private void moveToNewPosition() {
        List<LocalPosition> reachableTiles = getWalker().getCollisionManager().findReachableTiles(getLocalPosition(), 6);
        if (reachableTiles.isEmpty()) {
            log(getClass().getSimpleName(), "No reachable tiles found.");
            return;
        }

        getWalker().getSettings().setBreakDistance(1);
        getWalker().getSettings().setTileRandomisationRadius(1);
        LocalPosition randomPos = reachableTiles.get(random(reachableTiles.size()));
        getWalker().walkTo(randomPos);
        forceNewPosition = false;
    }

    private void waitForFireToLight(WorldPosition lightPosition) {
        log(getClass().getSimpleName(), "Waiting for fire to light...");
        boolean result = submitHumanTask(() -> {
            WorldPosition currentPos = getWorldPosition();
            return currentPos != null && !currentPos.equals(lightPosition);
        }, 14000);

        if (result) {
            logsBurnt++;
            bonfirePosition = lightPosition;
        }
    }

    private void burnLogsOnBonfire(UIResultList<ItemSearchResult> logs) {
        // check if bonfire is active
        RSTile tile = getSceneManager().getTile(bonfirePosition);
        if (tile == null || !tile.isOnGameScreen()) {
            log(getClass().getSimpleName(), "Walking to bonfire");
            // walk to tile
            getWalker().getSettings().setBreakDistance(1);
            getWalker().getSettings().setTileRandomisationRadius(1);
            getWalker().walkTo(tile.getWorldPosition(), () -> tile.isOnGameScreen());
            return;
        }
        Polygon tileCube = tile.getTileCube(70);
        if (tileCube == null) {
            return;
        }
        log(getClass().getSimpleName(), "Burning logs on the bonfire...");
        // use log on bonfire
        ItemSearchResult log = logs.get(random(logs.size()));
        RSTile fireTile = getSceneManager().getTile(bonfirePosition);
        if (!interactAndWaitForDialogue(log, fireTile)) {
            // walk a few tiles away, probably another camp fire close by
            LocalPosition myPos = getLocalPosition();
            List<LocalPosition> nearbyPositions = getWalker().getCollisionManager().findReachableTiles(myPos, 10);
            nearbyPositions.removeIf(localPosition -> myPos.distanceTo(localPosition) < 7);
            LocalPosition posToWalk = nearbyPositions.get(random(nearbyPositions.size()));
            bonfireTargetCreationPos = posToWalk.toWorldPosition(this);
            bonfirePosition = null;
        }
    }


    public boolean interactAndWaitForDialogue(ItemSearchResult log, RSTile fireTile) {
        if (!getItemManager().unSelectItemIfSelected()) {
            return false;
        }

        if (!log.interact()) {
            return true;
        }
        Polygon tilePoly = fireTile.getTilePoly();
        if (tilePoly == null) return false;

        // resize the poly to minimise missclicks
        tilePoly = tilePoly.getResized(0.3);
        if (!getFinger().tap(tilePoly, "Use " + logName + " -> fire", "Use " + logName + " -> Forester's Campfire")) {
            bonfirePosition = null;
            return true;
        }
        log(getClass().getSimpleName(), "Waiting for dialogue");
        // sleep until dialogue is visible
        return submitTask(() -> {
            DialogueType dialogueType1 = getWidgetManager().getDialogue().getDialogueType();
            if (dialogueType1 == null) return false;
            return dialogueType1 == DialogueType.ITEM_OPTION;
        }, 3000);
    }

    public void waitUntilFinishedBurning(int selectedLogsID) {
        Timer amountChangeTimer = new Timer();
        AtomicInteger previousAmount_ = new AtomicInteger(-1);
        submitHumanTask(() -> {
            try {
                DialogueType dialogueType = getWidgetManager().getDialogue().getDialogueType();
                if (dialogueType != null) {
                    if (dialogueType == DialogueType.TAP_HERE_TO_CONTINUE) {
                        submitTask(() -> false, random(1000, 4000));
                        return true;
                    }
                }

                if (amountChangeTimer.timeElapsed() > TimeUnit.SECONDS.toMillis(AMOUNT_CHANGE_TIMEOUT_SECONDS)) {
                    // usually happens when the bonfire extinguishes, so we clear our known bonfire position
                    bonfirePosition = null;
                    return true;
                }
                if (!getWidgetManager().getInventory().open()) {
                    return false;
                }
                UIResultList<ItemSearchResult> resourceResult = getItemManager().findAllOfItem(getWidgetManager().getInventory(), selectedLogsID);
                if (resourceResult.isNotVisible()) {
                    return false;
                }
                if (resourceResult.isEmpty()) {
                    return true;
                }
                int amount = resourceResult.size();

                int previousAmount = previousAmount_.get();
                if (amount < previousAmount || previousAmount == -1) {
                    int diff = Math.abs(amount - previousAmount);
                    logsBurntOnFire += diff;
                    previousAmount_.set(amount);
                    amountChangeTimer.reset();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return false;
        }, 80000, true, false, true);
    }

    @Override
    public int[] regionsToPrioritise() {
        return new int[]{12598};
    }

    @Override
    public boolean promptBankTabDialogue() {
        return true;
    }
}