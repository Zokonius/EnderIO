package crazypants.enderio.machines.integration.jei;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;

import crazypants.enderio.base.EnderIO;
import crazypants.enderio.base.Log;
import crazypants.enderio.base.integration.jei.energy.EnergyIngredient;
import crazypants.enderio.base.integration.jei.energy.EnergyIngredientRenderer;
import crazypants.enderio.base.power.PowerHandlerUtil;
import crazypants.enderio.machines.init.MachineObject;
import crazypants.enderio.machines.machine.wired.ContainerWiredCharger;
import crazypants.enderio.machines.machine.wired.GuiWiredCharger;
import mezz.jei.api.IGuiHelper;
import mezz.jei.api.IModRegistry;
import mezz.jei.api.gui.IDrawable;
import mezz.jei.api.gui.IDrawableAnimated;
import mezz.jei.api.gui.IGuiIngredientGroup;
import mezz.jei.api.gui.IGuiItemStackGroup;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.recipe.BlankRecipeCategory;
import mezz.jei.api.recipe.BlankRecipeWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.energy.IEnergyStorage;

public class WiredChargerRecipeCategory extends BlankRecipeCategory<WiredChargerRecipeCategory.WiredChargerRecipeWrapper> {

  public static final @Nonnull String UID = "EIOWC";

  // ------------ Recipes

  public static class WiredChargerRecipeWrapper extends BlankRecipeWrapper {

    private final @Nonnull ItemStack itemInput, itemOutput;
    private final @Nonnull EnergyIngredient energy;

    public WiredChargerRecipeWrapper(@Nonnull ItemStack itemInput, @Nonnull ItemStack itemOutput, int energy) {
      this.itemInput = itemInput;
      this.itemOutput = itemOutput;
      this.energy = new EnergyIngredient(energy);
    }

    @Override
    public void getIngredients(@Nonnull IIngredients ingredients) {
      ingredients.setInput(ItemStack.class, itemInput);
      ingredients.setOutput(ItemStack.class, itemOutput);
      ingredients.setInput(EnergyIngredient.class, energy);
    }

  } // -------------------------------------

  public static void register(IModRegistry registry, IGuiHelper guiHelper) {

    registry.addRecipeCategories(new WiredChargerRecipeCategory(guiHelper));
    registry.addRecipeCategoryCraftingItem(new ItemStack(MachineObject.block_wired_charger.getBlockNN()), WiredChargerRecipeCategory.UID);
    registry.addRecipeCategoryCraftingItem(new ItemStack(MachineObject.block_wireless_charger.getBlockNN()), WiredChargerRecipeCategory.UID);
    registry.addRecipeClickArea(GuiWiredCharger.class, 155, 42, 16, 16, WiredChargerRecipeCategory.UID);

    long start = System.nanoTime();

    List<ItemStack> validItems = registry.getIngredientRegistry().getIngredients(ItemStack.class);

    List<WiredChargerRecipeWrapper> result = new ArrayList<WiredChargerRecipeWrapper>();
    for (ItemStack stack : validItems) {
      if (PowerHandlerUtil.getCapability(stack, null) != null) {
        ItemStack copy = stack.copy();
        IEnergyStorage emptyCap = PowerHandlerUtil.getCapability(copy, null);
        if (emptyCap != null) {
          int extracted = 1;
          while (extracted > 0 && emptyCap.canExtract()) {
            extracted = emptyCap.extractEnergy(Integer.MAX_VALUE, false);
          }
          if (emptyCap.canReceive() && emptyCap.getEnergyStored() < emptyCap.getMaxEnergyStored()) {
            ItemStack empty = copy.copy();
            int added = emptyCap.receiveEnergy(Integer.MAX_VALUE, false);
            int power = added;
            while (added > 0) {
              power += added = emptyCap.receiveEnergy(Integer.MAX_VALUE, false);
            }
            result.add(new WiredChargerRecipeWrapper(empty, copy, power));
          }
        }
      }
    }

    long end = System.nanoTime();
    registry.addRecipes(result, UID);

    registry.getRecipeTransferRegistry().addRecipeTransferHandler(ContainerWiredCharger.class, WiredChargerRecipeCategory.UID,
        ContainerWiredCharger.FIRST_RECIPE_SLOT, ContainerWiredCharger.NUM_RECIPE_SLOT, ContainerWiredCharger.FIRST_INVENTORY_SLOT,
        ContainerWiredCharger.NUM_INVENTORY_SLOT);

    Log.info(String.format("WiredChargerRecipeCategory: Added %d item charging recipes to JEI in %.3f seconds.", result.size(), (end - start) / 1000000000d));
  }

  // ------------ Category

  private final @Nonnull IDrawable background;
  private final @Nonnull IDrawableAnimated flame;

  public WiredChargerRecipeCategory(IGuiHelper guiHelper) {
    ResourceLocation backgroundLocation = EnderIO.proxy.getGuiTexture("wired_charger");
    background = guiHelper.createDrawable(backgroundLocation, 28, 15, 100, 49, 0, 0, 46, 0);
    flame = guiHelper.createAnimatedDrawable(guiHelper.createDrawable(backgroundLocation, 176, 0, 14, 37), 200, IDrawableAnimated.StartDirection.TOP, false);
  }

  @Override
  public @Nonnull String getUid() {
    return UID;
  }

  @Override
  public @Nonnull String getTitle() {
    return MachineObject.block_wired_charger.getBlockNN().getLocalizedName();
  }

  @Override
  public @Nonnull IDrawable getBackground() {
    return background;
  }

  @Override
  public void drawExtras(@Nonnull Minecraft minecraft) {
    flame.draw(minecraft, 99, 2);
  }

  @SuppressWarnings("null")
  @Override
  public void setRecipe(@Nonnull IRecipeLayout recipeLayout, @Nonnull WiredChargerRecipeWrapper recipeWrapper, @Nonnull IIngredients ingredients) {
    IGuiItemStackGroup guiItemStacks = recipeLayout.getItemStacks();
    IGuiIngredientGroup<EnergyIngredient> group = recipeLayout.getIngredientsGroup(EnergyIngredient.class);

    guiItemStacks.init(0, true, 71, 12);
    guiItemStacks.init(1, false, 122, 12);
    group.init(2, true, EnergyIngredientRenderer.INSTANCE, 0, 16, 70, 10, 0, 0);

    guiItemStacks.set(ingredients);
    group.set(ingredients);
  }

}