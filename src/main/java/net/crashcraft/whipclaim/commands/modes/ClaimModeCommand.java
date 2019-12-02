package net.crashcraft.whipclaim.commands.modes;

import co.aikar.commands.annotation.Default;
import co.aikar.commands.annotation.Subcommand;
import net.crashcraft.whipclaim.WhipClaim;
import net.crashcraft.whipclaim.claimobjects.Claim;
import net.crashcraft.whipclaim.claimobjects.PermState;
import net.crashcraft.whipclaim.data.ClaimDataManager;
import net.crashcraft.whipclaim.data.ClaimResponse;
import net.crashcraft.whipclaim.data.ErrorType;
import net.crashcraft.whipclaim.data.StaticClaimLogic;
import net.crashcraft.whipclaim.permissions.PermissionRoute;
import net.crashcraft.whipclaim.permissions.PermissionRouter;
import net.crashcraft.whipclaim.visualize.*;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;

public class ClaimModeCommand implements Listener, ClaimModeProvider {
    private ClaimDataManager manager;
    private VisualizationManager visualizationManager;

    private ArrayList<UUID> enabledMode;
    private HashMap<UUID, Location> clickMap;
    private HashMap<UUID, Claim> resizingMap;

    public ClaimModeCommand(ClaimDataManager manager, VisualizationManager visualizationManage){
        this.manager = manager;
        this.visualizationManager = visualizationManage;

        enabledMode = new ArrayList<>();
        clickMap = new HashMap<>();
        resizingMap = new HashMap<>();
    }

    public void onClaim(Player player){
        UUID uuid = player.getUniqueId();
        if (enabledMode.contains(uuid)){
            player.sendMessage(ChatColor.RED + "Claim mode disabled");
            enabledMode.remove(uuid);
            clickMap.remove(uuid);
        } else {
            enabledMode.add(uuid);

            visualizationManager.visualizeSuroudningClaims(player, manager);

            player.sendMessage(ChatColor.GREEN + "Claim mode enabled, click 2 corners to claim.");
        }
    }

    @EventHandler
    public void onClick(PlayerInteractEvent e){
        if (enabledMode.contains(e.getPlayer().getUniqueId()) && e.getHand() != null && e.getHand().equals(EquipmentSlot.HAND) && e.getClickedBlock() != null){
            click(e.getPlayer(), e.getClickedBlock().getLocation());
        }
    }

    public void customEntityClick(Player player, Location location){
        if (enabledMode.contains(player.getUniqueId())){
            click(player, location);
        }
    }

    public void click(Player player, Location loc1){
        World world = loc1.getWorld();

        if (world == null) {
            cleanup(player.getUniqueId(), true);
            throw new RuntimeException("World was null on claim mode manager click");
        }

        Claim claim = manager.getClaim(loc1.getBlockX(), loc1.getBlockZ(), loc1.getWorld().getUID());

        if (claim != null || resizingMap.containsKey(player.getUniqueId())){
            clickedExistingClaim(player, loc1, claim);
            return;
        }

        clickClaim(player, loc1);
    }

    public void clickClaim(Player player, Location location){
        //TODO check for disabled worlds and disabled locations - bypass mode should bypass this

        UUID uuid = player.getUniqueId();
        UUID world = player.getWorld().getUID();

        if (!clickMap.containsKey(uuid)){
            clickMap.put(uuid, location);
            player.sendMessage(ChatColor.GREEN + "Click the an opposite corner to form a new claim.");

            VisualGroup group = visualizationManager.fetchVisualGroup(player, true);
            group.removeAllVisualsOfType(VisualType.MARKER);

            MarkerVisual visual = new MarkerVisual(location);
            group.addVisual(visual);

            visual.spawn();
            visual.color(TeamColor.YELLOW);
            return;
        }

        Location loc2 = clickMap.get(uuid);

        Location upperCorner = StaticClaimLogic.calculateUpperCorner(loc2, location);
        Location lowerCorner = StaticClaimLogic.calculateLowerCorner(loc2, location);

        if ((lowerCorner.getBlockX() - upperCorner.getBlockX()) < 4 || (lowerCorner.getBlockZ() - upperCorner.getBlockZ()) < 4) {
            player.sendMessage(ChatColor.RED + "A claim has to be at least a 5x5");
            cleanup(player.getUniqueId(),true);
            return;
        }

        if (manager.checkOverLapSurroudningClaims(-1, upperCorner.getBlockX(), upperCorner.getBlockZ(), lowerCorner.getBlockX(), lowerCorner.getBlockZ(), world)){
            player.sendMessage(ChatColor.RED + "You cannot claim over an existing claim.");
            cleanup(player.getUniqueId(), true);
            return;
        }

        ClaimResponse response = manager.createClaim(upperCorner, lowerCorner, uuid);

        if (response.isStatus()) {
            player.sendMessage(ChatColor.GREEN + "Claim has been successfully created.");

            VisualGroup group = visualizationManager.fetchVisualGroup(player, true);
            group.removeAllVisuals();

            ClaimVisual claimVisual = new ClaimVisual(response.getClaim(), player.getLocation().getBlockY() - 1);
            group.addVisual(claimVisual);

            claimVisual.spawn();
            claimVisual.color(TeamColor.GREEN);

            visualizationManager.despawnAfter(claimVisual, 30);

            cleanup(player.getUniqueId(), false);
        } else {
            player.sendMessage(ChatColor.RED + "Error creating claim");
            cleanup(player.getUniqueId(), true);
        }
    }

    public void clickedExistingClaim(Player player, Location location, Claim claim){
        UUID uuid = player.getUniqueId();

        if (claim == null) {    //Already have it in the clickmap
            claim = resizingMap.get(player.getUniqueId());
            Location loc1 = clickMap.get(uuid);

            if (claim == null)
                return;

            ErrorType error = manager.resizeClaim(claim, loc1.getBlockX(), loc1.getBlockZ(), location.getBlockX(), location.getBlockZ(),
                    (arr) -> {
                    //TODO  Do payments in here
                        return ErrorType.NONE;
                    });

            switch (error){
                case TOO_SMALL:
                    player.sendMessage(ChatColor.RED + "A claim has to be at least a 5x5");
                    cleanup(uuid, true);
                    break;
                case CANNOT_FLIP_ON_RESIZE:
                    player.sendMessage(ChatColor.RED + "Claims cannot be flipped, please retry and grab the other edge to expand in this direction");
                    cleanup(player.getUniqueId(), true);
                    break;
                case NONE:
                    player.sendMessage(ChatColor.GREEN + "Claim has been successfully resized");

                    VisualGroup group = visualizationManager.fetchVisualGroup(player, true);

                    group.removeAllVisuals();
                    ClaimVisual visual = new ClaimVisual(claim, player.getLocation().getBlockY() - 1);
                    group.addVisual(visual);

                    visual.spawn();
                    visual.color(TeamColor.GREEN);

                    visualizationManager.despawnAfter(visual, 30);

                    cleanup(uuid, false);
                    break;
            }
            return;
        }

        if (isClaimBorder(claim.getUpperCornerX(), claim.getLowerCornerX(), claim.getUpperCornerZ(),
                claim.getLowerCornerZ(), location.getBlockX(), location.getBlockZ())){
            if (PermissionRouter.getLayeredPermission(claim, null, player.getUniqueId(), PermissionRoute.MODIFY_CLAIM) == PermState.ENABLED
                    || claim.getOwner().equals(uuid)){

                clickMap.put(uuid, location);
                resizingMap.put(uuid, claim);

                claim.setResizing(true);

                VisualGroup group = visualizationManager.fetchVisualGroup(player, true);

                group.removeAllVisuals();
                ClaimVisual visual = new ClaimVisual(claim, player.getLocation().getBlockY() - 1);
                group.addVisual(visual);

                visual.spawn();
                visual.color(TeamColor.GOLD);

                player.sendMessage(ChatColor.GREEN + "Click another location to resize the claim.");
            } else {
                player.sendMessage(ChatColor.RED + "You do not have permission to modify this claim.");
            }
        } else {
            player.sendMessage(ChatColor.RED + "Unable to claim over an existing claim.");
        }
    }

    private void cleanup(UUID uuid, boolean visuals){
        clickMap.remove(uuid);
        enabledMode.remove(uuid);

        if (resizingMap.containsKey(uuid)){
            resizingMap.get(uuid).setResizing(false);
            resizingMap.remove(uuid);
        }

        if (visuals) {
            VisualGroup group = visualizationManager.fetchExistingGroup(uuid);
            if (group != null) {
                group.removeAllVisuals();
            }
        }
    }

    @Override
    public void cleanup(UUID uuid) {
        cleanup(uuid, true);
    }

    private static boolean isClaimBorder(int NWCorner_x, int SECorner_x, int NWCorner_z, int SECorner_z, int Start_x, int Start_z) {
        return Start_x == NWCorner_x || Start_x == SECorner_x || Start_z == NWCorner_z || Start_z == SECorner_z;
    }
}