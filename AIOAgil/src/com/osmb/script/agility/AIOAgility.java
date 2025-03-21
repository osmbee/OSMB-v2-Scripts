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
import com.osmb.api.utils.RandomUtils;
import com.osmb.api.utils.UIResult;
import com.osmb.api.utils.UIResultList;
import com.osmb.api.utils.Utils;
import com.osmb.api.utils.timing.Stopwatch;
import com.osmb.api.utils.timing.Timer;
import com.osmb.api.visual.SearchablePixel;
import com.osmb.api.visual.color.ColorModel;
import com.osmb.api.visual.color.tolerance.ToleranceComparator;
import com.osmb.api.visual.color.tolerance.impl.ChannelThresholdComparator;
import com.osmb.api.visual.drawing.Canvas;
import com.osmb.script.agility.courses.ardougne.Ardougne;
import com.osmb.script.agility.courses.pollnivneach.Pollnivneach;
import com.osmb.script.agility.ui.javafx.UI;
import javafx.scene.Scene;

import java.awt.*;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@ScriptDefinition(name = "AIO Agility", author = "Joe", version = 1.0, description = "Provides support over a range of agility courses.", skillCategory = SkillCategory.AGILITY)
public class AIOAgility extends Script {
    //default options
    public static final int DEFAULT_EAT_LOW = 30;
    public static final int DEFAULT_EAT_HIGH = 75;
    public static final String[] BANK_NAMES = {"Bank", "Chest", "Bank booth", "Bank chest", "Grand Exchange booth"};
    public static final String[] BANK_ACTIONS = {"bank", "open"};
    private static final ToleranceComparator MOG_TOLERANCE_COMPARATOR = new ChannelThresholdComparator(2, 2, 2);
    private static final SearchablePixel[] MOG_PIXELS = new SearchablePixel[]{new SearchablePixel(-4414953, MOG_TOLERANCE_COMPARATOR, ColorModel.HSL), new SearchablePixel(-6741740, MOG_TOLERANCE_COMPARATOR, ColorModel.HSL)};
    private static final int[] ITEMS_TO_IGNORE = new int[]{ItemID.MARK_OF_GRACE, ItemID.LAW_RUNE, ItemID.AIR_RUNE, ItemID.FIRE_RUNE};
    private static final WorldPosition ARDY_MOG_POS = new WorldPosition(2657, 3318, 3);
    private static final WorldPosition POLL_MOG_POS = new WorldPosition(3359, 2983, 2);
    private final Stopwatch eatBlockTimer = new Stopwatch();
    private Course selectedCourse;
    private int foodItemID = -1;
    private int hitpointsToEat = -1;
    private int eatHigh;
    private int eatLow;
    private int nextRunActivate;
    private int noMovementTimeout = RandomUtils.weightedRandom(3000, 6000);

    // to handle the osrs glitch where the position doesn't update
    private int failTimes = 0;
    private int failThreshold = random(3, 6);

    public AIOAgility(Object object) {
        super(object);
    }

    public static boolean handleMOG(AIOAgility core) {
        UIResultList<WorldPosition> groundItems = core.getWidgetManager().getMinimap().getItemPositions();
        if (!groundItems.isFound()) {
            return false;
        }

        WorldPosition myPosition = core.getWorldPosition();
        for (WorldPosition groundItem : groundItems) {
            core.log("Ground item found");
            RSTile tile = core.getSceneManager().getTile(groundItem);
            if (tile == null) {
                core.log(AIOAgility.class.getSimpleName(), "Tile is null.");
                continue;
            }

            if (!tile.isOnGameScreen()) {
                core.log(AIOAgility.class.getSimpleName(), "WARNING: Tile containing item is not on screen, reduce your zoom level.");
                continue;
            }

            // handle ardy mog tile as we can't reach this one
            if (tile.getWorldPosition().equals(ARDY_MOG_POS) || tile.getWorldPosition().equals(POLL_MOG_POS)) {
                if (!Ardougne.AREA_3.contains(myPosition)) {
                    continue;
                }
                if (!Pollnivneach.AREA_6.contains(myPosition)) {
                    continue;
                }
            } else if (!tile.canReach()) {
                continue;
            }

            Polygon tilePoly = tile.getTilePoly();
            if (tilePoly == null) {
                core.log(AIOAgility.class.getSimpleName(), "Tile poly is null.");
                continue;
            }
            core.log("Checking ground item for MOG");
            tilePoly = tilePoly.getResized(0.9);
            // if the tile contains all pixels
            boolean found = true;
            for (SearchablePixel mogPixel : MOG_PIXELS) {
                Point result = core.getPixelAnalyzer().findPixel(tilePoly, mogPixel);
                if (result == null) {
                    found = false;
                    break;
                } else {
                    core.log("Failed to find pixel: " + mogPixel.getRgb());
                }
            }
            if (!found) {
                continue;
            }

            UIResult<ItemSearchResult> mog = core.getItemManager().findItem(core.getWidgetManager().getInventory(), ItemID.MARK_OF_GRACE);
            if (mog.isNotFound()) {
                // check if we have free spaces
                Optional<Integer> freeSlots = core.getItemManager().getFreeSlotsInteger(core.getWidgetManager().getInventory());
                if (freeSlots.isPresent() && freeSlots.get() <= 0) {
                    core.log(AIOAgility.class.getSimpleName(), "MOG Found but no inventory slots free in the inventory...");
                    UIResultList<ItemSearchResult> food = core.getItemManager().findAllOfItem(core.getWidgetManager().getInventory(), core.foodItemID);
                    if (food.isNotVisible()) {
                        return false;
                    }
                    if (!food.isEmpty()) {
                        core.log(AIOAgility.class.getSimpleName(), "Eating food to make space for MOG!");
                        if (!food.getRandom().interact("eat", "drink")) {
                            return false;
                        }
                    } else {
                        core.log(AIOAgility.class.getSimpleName(), "No room to pick up MOG.");
                        core.stop();
                    }
                }
            }
            core.log(AIOAgility.class.getSimpleName(), "Attempting to interact with MOG");
            Polygon polygon = tilePoly.getResized(0.6);
            if (core.getFinger().tap(polygon, "Take mark of grace")) {

                // sleep until we picked up the mark
                core.submitHumanTask(() -> {
                    WorldPosition position = core.getWorldPosition();
                    if (position == null) return false;
                    return position.equals(tile.getWorldPosition());
                }, 7000);
            }
            return true;
        }

        return false;
    }

    /**
     * Handles an agility obstacle, will run to & interact using the specified {@param menuOption} then sleep until we reach then {@param endPosition}
     *
     * @param core
     * @param obstacleName The name of the obstacle
     * @param menuOption   The name of the menu option to select
     * @param end          The finishing {@link WorldPosition} or {@link Area} of the obstacle interaction
     * @param timeout      The timeout when to the {@param endPosition}, method will return {@link ObstacleHandleResponse#TIMEOUT} if the specified timeout is surpassed
     * @return
     */
    public static ObstacleHandleResponse handleObstacle(AIOAgility core, String obstacleName, String menuOption, Object end, int timeout) {
        return handleObstacle(core, obstacleName, menuOption, end, 1, timeout);
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
    public static ObstacleHandleResponse handleObstacle(AIOAgility core, String obstacleName, String menuOption, Object end, int interactDistance, int timeout) {
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
     * @param canReach         If {@code false} then this method will avoid using {@link RSObject#canReach()} when querying objects for the obstacle.
     * @param timeout          The timeout when to the {@param endPosition}, method will return {@link ObstacleHandleResponse#TIMEOUT} if the specified timeout is surpassed
     * @return
     */
    public static ObstacleHandleResponse handleObstacle(AIOAgility core, String obstacleName, String menuOption, Object end, int interactDistance, boolean canReach, int timeout) {
        return handleObstacle(core, obstacleName, menuOption, end, interactDistance, canReach, timeout, null);
    }

    /**
     * Handles an agility obstacle, will run to & interact using the specified {@param menuOption} then sleep until we reach then {@param endPosition}
     *
     * @param core
     * @param obstacleName     The name of the obstacle
     * @param menuOption       The name of the menu option to select
     * @param end              The finishing {@link WorldPosition} or {@link Area} of the obstacle interaction
     * @param interactDistance The tile distance away from the object which it can be interacted from.
     * @param canReach         If {@code false} then this method will avoid using {@link RSObject#canReach()} when querying objects for the obstacle.
     * @param timeout          The timeout when to the {@param endPosition}, method will return {@link ObstacleHandleResponse#TIMEOUT} if the specified timeout is surpassed
     * @param objectBaseTile   The base tile of the object. If null we avoid this check.
     * @return
     */
    public static ObstacleHandleResponse handleObstacle(AIOAgility core, String obstacleName, String menuOption, Object end, int interactDistance, boolean canReach, int timeout, WorldPosition objectBaseTile) {
        // cache hp, we determine if we failed the obstacle via hp decrementing
        UIResult<Integer> hitpoints = core.getWidgetManager().getMinimapOrbs().getHitpointsPercentage();
        Optional<RSObject> result = core.getObjectManager().getObject(gameObject -> {

            if (gameObject.getName() == null || gameObject.getActions() == null) return false;

            if (!gameObject.getName().equalsIgnoreCase(obstacleName)) {
                return false;
            }

            if (objectBaseTile != null) {
                if (!objectBaseTile.equals(gameObject.getWorldPosition())) {
                    return false;
                }
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
        if (object.interact(interactDistance, new String[]{menuOption})) {
            core.log(AIOAgility.class.getSimpleName(), "Interacted successfully, sleeping until conditions are met...");
            Timer noMovementTimer = new Timer();
            AtomicReference<WorldPosition> previousPosition = new AtomicReference<>();
            if (core.submitHumanTask(() -> {
                WorldPosition currentPos = core.getWorldPosition();
                if (currentPos == null) {
                    return false;
                }
                // check if we take damage
                if (hitpoints.isFound()) {
                    UIResult<Integer> newHitpointsResult = core.getWidgetManager().getMinimapOrbs().getHitpointsPercentage();
                    if (newHitpointsResult.isFound()) {
                        if (hitpoints.get() > newHitpointsResult.get()) {
                            return true;
                        }
                    }
                }
                // check for being stood still
                if (previousPosition.get() != null) {
                    if (currentPos.equals(previousPosition.get())) {
                        if (noMovementTimer.timeElapsed() > core.noMovementTimeout) {
                            core.noMovementTimeout = RandomUtils.weightedRandom(2000, 6000);
                            core.failTimes++;
                            return true;
                        }
                    } else {
                        noMovementTimer.reset();
                    }
                } else {
                    noMovementTimer.reset();
                }
                previousPosition.set(currentPos);

                RSTile tile = core.getSceneManager().getTile(core.getWorldPosition());
                Polygon poly = tile.getTileCube(120);
                if (core.getPixelAnalyzer().isAnimating(0.1, poly)) {
                    return false;
                }
                if (end instanceof Area area) {
                    if (area.contains(currentPos)) {
                        core.failTimes = 0;
                        core.failThreshold = Utils.random(3, 6);
                        return true;
                    }
                } else if (end instanceof Position pos) {
                    if (currentPos.equals(pos)) {
                        core.failTimes = 0;
                        core.failThreshold = Utils.random(3, 6);
                        return true;
                    }
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
        Scene scene = ui.buildScene(this);
        getStageController().show(scene, "Settings", false);

        // set the selected course
        this.selectedCourse = ui.selectedCourse();
        this.foodItemID = ui.foodItemID();
        this.eatHigh = ui.getEatHigh();
        this.eatLow = ui.getEatLow();
        this.hitpointsToEat = random(eatLow, eatHigh);
        this.nextRunActivate = random(30, 70);
    }

    @Override
    public int onRelog() {
        failTimes = 0;
        return 0;
    }

    @Override
    public int poll() {
        // handle eating food & banking
        if (getWidgetManager().getBank().isVisible()) {
            if (foodItemID != -1) {
                handleBankInterface();
            } else {
                getWidgetManager().getBank().close();
            }
            return 0;
        }
        if (failTimes > failThreshold) {
            getWidgetManager().getLogoutTab().logout();
            return 0;
        }
        if (foodItemID != -1) {
            UIResult<Integer> hpOpt = getWidgetManager().getMinimapOrbs().getHitpointsPercentage();
            if (!hpOpt.isFound()) {
                log(getClass().getSimpleName(), "Hitpoints orb not visible...");
                return 0;
            }
            UIResultList<ItemSearchResult> food = getItemManager().findAllOfItem(getWidgetManager().getInventory(), foodItemID);
            // walk to bank to restock, stop script if course doesn't have a bank
            if (food.isEmpty()) {
                // if on the ground floor
                WorldPosition position = getWorldPosition();
                if (position != null && position.getPlane() == 0) {
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
            } else if (food.isFound()) {
                int hitpoints = hpOpt.get();
                log(getClass().getSimpleName(), "Hitpoints: " + hitpoints + "%" + " Block timer finished: " + eatBlockTimer.hasFinished() + " Eating at: " + hitpoints + "%");
                if (hitpoints <= hitpointsToEat && eatBlockTimer.hasFinished()) {
                    // eat food
                    ItemSearchResult foodToEat = food.getRandom();
                    foodToEat.interact();
                    eatBlockTimer.reset(3000);
                    hitpointsToEat = random(eatLow, eatHigh);
                }
            }
        }

        UIResult<Boolean> runEnabled = getWidgetManager().getMinimapOrbs().isRunEnabled();
        if (runEnabled.isFound()) {
            UIResult<Integer> runEnergyOpt = getWidgetManager().getMinimapOrbs().getRunEnergy();
            int runEnergy = runEnergyOpt.orElse(-1);
            if (!runEnabled.get() && runEnergy > nextRunActivate) {
                log(getClass().getSimpleName(), "Enabling run");
                if (!getWidgetManager().getMinimapOrbs().setRun(true)) {
                    return 0;
                }
                nextRunActivate = random(30, 70);
            }
        }
        WorldPosition position = getWorldPosition();
        if (position == null) {
            log(getClass().getSimpleName(), "Position is null.");
            return 0;
        }
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
            submitTask(() -> getWidgetManager().getBank().isVisible(), 10000);
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
        if (foodInBank.get() == null) {
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
