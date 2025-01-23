package com.osmb.script.agility;

import com.osmb.api.ScriptCore;
import com.osmb.api.item.ItemID;
import com.osmb.api.item.ItemSearchResult;
import com.osmb.api.location.area.Area;
import com.osmb.api.location.position.Position;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.scene.RSObject;
import com.osmb.api.scene.RSTile;
import com.osmb.api.script.Script;
import com.osmb.api.script.ScriptDefinition;
import com.osmb.api.script.SkillCategory;
import com.osmb.api.shape.Polygon;
import com.osmb.api.utils.UIResult;
import com.osmb.api.utils.UIResultList;
import com.osmb.api.utils.timing.Stopwatch;
import com.osmb.api.visual.SearchablePixel;
import com.osmb.api.visual.color.ColorModel;
import com.osmb.api.visual.color.tolerance.ToleranceComparator;
import com.osmb.api.visual.color.tolerance.impl.ChannelThresholdComparator;
import com.osmb.api.visual.drawing.Canvas;
import com.osmb.script.agility.ui.javafx.UI;
import javafx.scene.Scene;

import java.awt.*;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@ScriptDefinition(name = "AIO Agility", author = "Joe", version = 1.0, description = "Provides support over a range of agility courses.", skillCategory = SkillCategory.AGILITY)
public class AIOAgility extends Script {
    //default options
    public static final int DEFAULT_EAT_LOW = 30;
    public static final int DEFAULT_EAT_HIGH = 75;
    public static final String[] BANK_NAMES = {"Bank", "Chest", "Bank booth", "Bank chest", "Grand Exchange booth"};
    public static final String[] BANK_ACTIONS = {"bank", "open"};
    private static final ToleranceComparator MOG_TOLERANCE_COMPARATOR = new ChannelThresholdComparator(10, 10, 3);
    private static final SearchablePixel MOG_PIXEL = new SearchablePixel(-3822823, MOG_TOLERANCE_COMPARATOR, ColorModel.HSL);
    private static final int[] ITEMS_TO_IGNORE = new int[]{ItemID.MARK_OF_GRACE, ItemID.LAW_RUNE, ItemID.AIR_RUNE, ItemID.FIRE_RUNE};
    private final Stopwatch eatBlockTimer = new Stopwatch();
    private Course selectedCourse;
    private int foodItemID = -1;
    private int hitpointsToEat = -1;
    private int eatHigh;
    private int eatLow;
    private int nextRunActivate;

    public AIOAgility(Object object) {
        super(object);
    }

    public static boolean handleMOG(ScriptCore core) {
        UIResultList<WorldPosition> groundItems = core.getWidgetManager().getMinimap().getItemPositions();
        if (!groundItems.isFound()) {
            return false;
        }
        for (WorldPosition groundItem : groundItems) {
            RSTile tile = core.getSceneManager().getTile(groundItem);
            if (tile == null) {
                core.log(AIOAgility.class.getSimpleName(), "Tile is null.");
                continue;
            }

            if (!tile.isOnGameScreen()) {
                core.log(AIOAgility.class.getSimpleName(), "WARNING: Tile containing item is not on screen, reduce your zoom level.");
                continue;
            }

            if (!tile.canReach()) {
                continue;
            }

            Polygon tilePoly = tile.getTilePoly();
            if (tilePoly == null) {
                core.log(AIOAgility.class.getSimpleName(), "Tile poly is null.");
                continue;
            }
            List<Point> results = core.getPixelAnalyzer().findPixels(tilePoly, MOG_PIXEL);
            core.log("Results: " + results.size());
            // check for MOG
            Point result = core.getPixelAnalyzer().findPixel(tilePoly, MOG_PIXEL);
            if (result == null) {
                core.log(AIOAgility.class.getSimpleName(), "Can't find MOG pixels in tile...");
                continue;
            } else {
                core.log(String.valueOf(result));
            }

            core.log(AIOAgility.class.getSimpleName(), "Attempting to interact with MOG");
            Polygon polygon = tilePoly.getResized(0.6);
            if (core.getFinger().tap(polygon, "Take mark of grace")) {

                // sleep until we picked up the mark
                core.submitTask(() -> {
                    WorldPosition position = core.getWorldPosition();
                    if (position == null) return false;
                    return position.equals(tile.getWorldPosition());
                }, 7000);
            }
            return true;
        }

        return false;
    }

    public static ObstacleHandleResponse handleObstacle(ScriptCore methods, String obstacleName, String menuOption, Object end, int timeout) {
        return handleObstacle(methods, obstacleName, menuOption, end, 1, timeout);
    }

    public static ObstacleHandleResponse handleObstacle(ScriptCore core, String obstacleName, String menuOption, Object end, int interactDistance, int timeout) {
        return handleObstacle(core, obstacleName, menuOption, end, interactDistance, true, timeout);
    }

    /**
     * Handles an agility obstacle, will run to & interact using the specified {@param menuOption} then sleep until we reach then {@param endPosition}
     *
     * @param core
     * @param obstacleName     The name of the obstacle
     * @param menuOption       The name of the menu option to select
     * @param end              The finishing {@link WorldPosition} or {@link Area} of the obstacle interaction
     * @param interactDistance The tile distance away from the object which it can be interacted from.
     * @param timeout          The timeout when to the {@param endPosition}, method will return {@link ObstacleHandleResponse#TIMEOUT} if the specified timeout is surpassed
     * @return
     */
    public static ObstacleHandleResponse handleObstacle(ScriptCore core, String obstacleName, String menuOption, Object end, int interactDistance, boolean canReach, int timeout) {
        Optional<RSObject> result = core.getObjectManager().getObject(gameObject -> {
            if (gameObject.getName() == null || gameObject.getActions() == null) return false;
            if (!gameObject.getName().equalsIgnoreCase(obstacleName)) {
                return false;
            }
            if (!canReach) {
                return true;
            }
            boolean canReach_ = gameObject.canReach(interactDistance);
            return canReach_;
        });
        if (!result.isPresent()) {
            core.log(AIOAgility.class.getSimpleName(), "ERROR: Obstacle (" + obstacleName + ") does not exist with criteria.");
            return ObstacleHandleResponse.OBJECT_NOT_IN_SCENE;
        }
        RSObject object = result.get();
        if (object.interact(interactDistance, menuOption)) {
            core.log(AIOAgility.class.getSimpleName(), "Interacted successfully, sleeping until conditions are met...");
            if (core.submitTask(() -> {
                WorldPosition currentPos = core.getWorldPosition();
                if (currentPos == null) {
                    return false;
                }
                RSTile tile = core.getSceneManager().getTile(core.getWorldPosition());
                Polygon poly = tile.getTileCube(120);
                if (core.getPixelAnalyzer().isAnimating(300, poly)) {
                    return false;
                }
                if (end instanceof Area area) {
                    return area.contains(currentPos);
                }
                if (end instanceof Position pos) {
                    return currentPos.equals(pos);
                }
                return false;
            }, timeout)) {
                return ObstacleHandleResponse.SUCCESS;
            } else {
                return ObstacleHandleResponse.TIMEOUT;
            }
        } else {
            core.log(AIOAgility.class.getSimpleName(), "ERROR: Failed interacting with obstacle (" + obstacleName + ").");
            return ObstacleHandleResponse.FAILED_INTERACTION;
        }
    }

    @Override
    public void onStart() {
        // show the UI on the javafx UI thread
        //            FXMLLoader loader = new FXMLLoader(AIOAgility.class.getResource("/ui.fxml"));
//            // initializing the controller
//            popupController = new Controller();
//            loader.setController(popupController);
//            Parent layout = loader.load();
//
//            // initialise our fxml's components actions
//            popupController.init();

        UI ui = new UI();
        Scene scene = ui.open();
        getStageController().show(scene, "Settings", false);

        // set the selected course
        selectedCourse = ui.selectedCourse();
        foodItemID = ui.foodItemID();
        eatHigh = ui.getEatHigh();
        eatLow = ui.getEatLow();
        hitpointsToEat = random(eatLow, eatHigh);
        nextRunActivate = random(30, 70);
    }

    @Override
    public int poll() {
        // handle eating food & banking
        if (foodItemID != -1) {
            UIResult<Integer> hpOpt = getWidgetManager().getMinimapOrbs().getHitpoints();
            if (!hpOpt.isFound()) {
                log(getClass().getSimpleName(), "Hitpoints orb not visible...");
                return 0;
            }
            UIResultList<ItemSearchResult> food = getItemManager().findAllOfItem(getWidgetManager().getInventory(), foodItemID);

            if (food.isFound()) {
                int hitpoints = hpOpt.get();
                if (hitpoints <= hitpointsToEat && eatBlockTimer.hasFinished()) {
                    // eat food
                    ItemSearchResult foodToEat = food.getRandom();
                    foodToEat.interact();
                    eatBlockTimer.reset(3000);
                    hitpointsToEat = random(eatLow, eatHigh);
                }
                if (getWidgetManager().getBank().isVisible()) {
                    handleBankInterface();
                    return 0;
                }
            }
            // walk to bank to restock, stop script if course doesn't have a bank
            WorldPosition position = getWorldPosition();
            if (position != null && position.getPlane() == 0) {
                if (food.isNotFound()) {
                    Area bankArea = selectedCourse.getBankArea();
                    if (bankArea != null) {
                        //restock
                        return navigateToBank();
                    } else {
                        stop();
                        log(getClass().getSimpleName(), "Ran out of food, stopping script...");
                        return 0;
                    }
                }
            }
        }

        UIResult<Boolean> runEnabled = getWidgetManager().getMinimapOrbs().isRunEnabled();
        if (runEnabled.isFound()) {
            UIResult<Integer> runEnergyOpt = getWidgetManager().getMinimapOrbs().getRunEnergy();
            int runEnergy = runEnergyOpt.orElse(-1);
            if (!runEnabled.get() && runEnergy > nextRunActivate) {
                if (!getWidgetManager().getMinimapOrbs().setRun(true)) {
                    return 0;
                }
                nextRunActivate = random(30, 70);
            }
        }
        WorldPosition position = getWorldPosition();
        if (position.getPlane() > 0 && handleMOG(this)) {
            return 0;
        }
        return selectedCourse.poll(this);
    }

    private int navigateToBank() {
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

        // walk to bank area
        if (banksFound.isEmpty()) {
            getWalker().walkTo(selectedCourse.getBankArea().getRandomPosition());
        } else {
            RSObject object = (RSObject) getUtils().getClosest(banksFound);
            if (!object.interact(BANK_ACTIONS)) return 200;
        }
        return 0;
    }

    /**
     * Handles restocking food from the bank
     */
    private void handleBankInterface() {
        int[] itemsToIgnore = new int[ITEMS_TO_IGNORE.length + 1];
        System.arraycopy(ITEMS_TO_IGNORE, 0, itemsToIgnore, 0, ITEMS_TO_IGNORE.length);
        itemsToIgnore[ITEMS_TO_IGNORE.length] = foodItemID;
        if (!getWidgetManager().getBank().depositAll(itemsToIgnore)) {
            return;
        }
        UIResult<ItemSearchResult> foodInBank = getItemManager().findItem(getWidgetManager().getBank(), foodItemID);
        if (foodInBank.isNotVisible()) {
            return;
        }
        if (foodInBank.isNotFound()) {
            log(getClass().getSimpleName(), "No food left in the bank, stopping script...");
            stop();
            return;
        }
        getWidgetManager().getBank().withdraw(foodItemID, 100);
    }

    @Override
    public int[] regionsToPrioritise() {
        if (selectedCourse == null) {
            return new int[0];
        }
        return selectedCourse.regions();
    }

    @Override
    public void onPaint(Canvas c) {
        if (selectedCourse == null) return;
        selectedCourse.onPaint(c);
    }
}
