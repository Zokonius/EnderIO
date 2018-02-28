package crazypants.enderio.base.filter.filters;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
// TODO Move to Capabilities

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.enderio.core.client.gui.widget.GhostSlot;
import com.enderio.core.common.NBTAction;
import com.enderio.core.common.network.NetworkUtil;
import com.enderio.core.common.util.NNList;
import com.enderio.core.common.util.NNList.Callback;
import com.enderio.core.common.util.NullHelper;

import crazypants.enderio.base.filter.IItemFilter;
import crazypants.enderio.base.filter.INetworkedInventory;
import crazypants.enderio.base.filter.filters.ItemFilter.HandleFilter;
import crazypants.enderio.base.filter.gui.IItemFilterContainer;
import crazypants.enderio.base.filter.gui.IItemFilterGui;
import crazypants.enderio.base.gui.GuiContainerBaseEIO;
import crazypants.enderio.base.init.ModObjectRegistry;
import crazypants.enderio.util.NbtValue;
import crazypants.enderio.util.Prep;
import info.loenwind.autosave.Registry;
import info.loenwind.autosave.annotations.Storable;
import info.loenwind.autosave.exceptions.NoHandlerFoundException;
import info.loenwind.autosave.handlers.IHandler;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.oredict.OreDictionary;

@Storable(handler = HandleFilter.class)
public class ItemFilter implements IInventory, IItemFilter {

  // Proxies to writetoNBT/readFromNBT
  // TODO ItemFilter could easily be naturally @Storable
  public static class HandleFilter implements IHandler<ItemFilter> {

    @Override
    public boolean canHandle(Class<?> clazz) {
      return ItemFilter.class.isAssignableFrom(clazz);
    }

    @Override
    public boolean store(@Nonnull Registry registry, @Nonnull Set<NBTAction> phase, @Nonnull NBTTagCompound nbt, @Nonnull String name,
        @Nonnull ItemFilter object) throws IllegalArgumentException, IllegalAccessException, InstantiationException, NoHandlerFoundException {
      NBTTagCompound root = new NBTTagCompound();
      object.writeToNBT(root);
      nbt.setTag(name, root);
      return true;
    }

    @Override
    public ItemFilter read(@Nonnull Registry registry, @Nonnull Set<NBTAction> phase, @Nonnull NBTTagCompound nbt, @Nullable Field field, @Nonnull String name,
        @Nullable ItemFilter object) throws IllegalArgumentException, IllegalAccessException, InstantiationException, NoHandlerFoundException {
      if (object == null) {
        // Note: This will be called with no nbt when a fresh itemstack is placed---output should be null!
        if (nbt.hasKey(name)) {
          object = new ItemFilter();
        } else {
          return object;
        }
      }
      object.readFromNBT(nbt.getCompoundTag(name));
      return object;
    }
  }

  private static final boolean DEFAULT_BLACKLIST = false;

  private static final boolean DEFAULT_META = true;

  private static final boolean DEFAULT_MBT = true;

  private static final boolean DEFAULT_ORE_DICT = false;

  private static final boolean DEFAULT_STICKY = false;

  private boolean isBlacklist = DEFAULT_BLACKLIST;
  private boolean matchMeta = true;
  private boolean matchNBT = true;
  private boolean useOreDict = false;
  private boolean sticky = false;
  private @Nonnull DamageMode damageMode = DamageMode.DISABLED;

  private @Nonnull NNList<ItemStack> items;

  private final @Nonnull List<int[]> oreIds;

  private boolean isAdvanced, isLimited;

  public void copyFrom(@Nonnull ItemFilter o) {
    isBlacklist = o.isBlacklist;
    matchMeta = o.matchMeta;
    matchNBT = o.matchNBT;
    useOreDict = o.useOreDict;
    sticky = o.sticky;
    damageMode = o.damageMode;
    items = o.items.copy();
    oreIds.clear();
    oreIds.addAll(o.oreIds);
    isAdvanced = o.isAdvanced;
    isLimited = o.isLimited;
  }

  public ItemFilter() {
    this(5, false);
  }

  public ItemFilter(boolean advanced) {
    this(advanced ? 10 : 5, advanced);
  }

  private ItemFilter(int numItems, boolean isAdvanced) {
    this.isAdvanced = isAdvanced;
    items = new NNList<ItemStack>(numItems, Prep.getEmpty());
    oreIds = new ArrayList<int[]>(numItems);
    for (int i = 0; i < numItems; i++) {
      oreIds.add(null);
    }
    isLimited = false;
  }

  public ItemFilter(int damage) {
    this(damage > 0);
    isLimited = damage > 1;
  }

  @Override
  public boolean doesItemPassFilter(@Nullable INetworkedInventory inv, @Nonnull ItemStack item) {
    return !isValid() || itemMatched(item).isPass(isBlacklist);
  }

  @Override
  public int getMaxCountThatPassesFilter(@Nullable INetworkedInventory inv, @Nonnull ItemStack item) {
    if (isValid()) {
      FilterResult value = itemMatched(item);
      if (isLimited && value.hasLimit()) {
        // Note: No blacklist for limited filters
        return value.getLimit();
      } else if (!isLimited && itemMatched(item).isPass(isBlacklist)) {
        return Integer.MAX_VALUE;
      }
    }
    return 0;
  }

  /**
   * Checks if the given item passes the filter.
   * 
   * @param item
   *          The item to check against the filter
   * @return a FilterResult
   */
  private FilterResult itemMatched(@Nonnull ItemStack item) {
    if (damageMode.passesFilter(item)) {
      // if there are no filter items, but a damage mode is set, the filter will let items pass that match that filter mode
      boolean canPassFilter = damageMode != DamageMode.DISABLED;
      for (int i = 0; i < items.size(); i++) {
        ItemStack filterStack = items.get(i);
        if (Prep.isValid(filterStack)) {
          if (item.getItem() == filterStack.getItem()) {
            if (!matchMeta || !item.getHasSubtypes() || item.getMetadata() == filterStack.getMetadata()) {
              if (!matchNBT || isNBTMatch(item, filterStack)) {
                return new FilterResult(filterStack.getCount());
              }
            }
          }
          if (useOreDict && isOreDicMatch(i, item)) {
            return new FilterResult(filterStack.getCount());
          }
          canPassFilter = false;
        }
      }
      return canPassFilter ? FilterResult.PASS : FilterResult.FAIL;
    }

    return FilterResult.FAIL;
  }

  private boolean isOreDicMatch(int filterItemIndex, @Nonnull ItemStack item) {
    int[] ids1 = getCachedIds(filterItemIndex);
    if (ids1.length == 0) {
      return false;
    }
    int[] ids2 = OreDictionary.getOreIDs(item);
    if (ids2 == null || ids2.length == 0) {
      return false;
    }
    for (int id1 : ids1) {
      for (int id2 : ids2) {
        if (id1 == id2) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean isNBTMatch(@Nonnull ItemStack filter, @Nonnull ItemStack item) {
    return getTag(filter).equals(getTag(item));
  }

  private static final @Nonnull NBTTagCompound EMPTY_NBT = new NBTTagCompound();

  private @Nonnull NBTTagCompound getTag(@Nonnull ItemStack item) {
    if (Prep.isInvalid(item) || !item.hasTagCompound()) {
      return EMPTY_NBT;
    }
    NBTTagCompound nbt = item.getTagCompound();
    if (nbt == null || nbt.hasNoTags()) {
      return EMPTY_NBT;
    }
    if (nbt.hasKey("GEN")) {
      // ignore Forestry bee generation (age of bee)
      (nbt = nbt.copy()).removeTag("GEN");
    }
    return nbt;
  }

  private @Nonnull int[] getCachedIds(int filterItemIndex) {
    int[] res = oreIds.get(filterItemIndex);
    if (res == null) {
      ItemStack item = items.get(filterItemIndex);
      if (Prep.isInvalid(item)) {
        res = new int[0];
      } else {
        res = OreDictionary.getOreIDs(item);
        if (res == null) {
          res = new int[0];
        }
      }
      oreIds.set(filterItemIndex, res);
    }
    return res;
  }

  @Override
  public boolean isValid() {
    if (damageMode != DamageMode.DISABLED) {
      return true;
    }
    for (ItemStack item : items) {
      if (item != null && Prep.isValid(item)) {
        return true;
      }
    }
    return false;
  }

  public boolean isBlacklist() {
    return isBlacklist;
  }

  public void setBlacklist(boolean isBlacklist) {
    this.isBlacklist = isBlacklist;
  }

  public boolean isMatchMeta() {
    return matchMeta;
  }

  public void setMatchMeta(boolean matchMeta) {
    this.matchMeta = matchMeta;
  }

  public boolean isMatchNBT() {
    return matchNBT;
  }

  public void setMatchNBT(boolean matchNbt) {
    matchNBT = matchNbt;
  }

  public boolean isUseOreDict() {
    return useOreDict;
  }

  public void setUseOreDict(boolean useOreDict) {
    this.useOreDict = useOreDict;
  }

  @Override
  public boolean isSticky() {
    return sticky;
  }

  public void setSticky(boolean sticky) {
    this.sticky = sticky;
  }

  public DamageMode getDamageMode() {
    return damageMode;
  }

  public void setDamageMode(@Nonnull DamageMode damageMode) {
    this.damageMode = damageMode;
  }

  @Override
  public void writeToNBT(@Nonnull NBTTagCompound nbtRoot) {
    NbtValue.FILTER_BLACKLIST.setBoolean(nbtRoot, isBlacklist);
    NbtValue.FILTER_META.setBoolean(nbtRoot, matchMeta);
    NbtValue.FILTER_NBT.setBoolean(nbtRoot, matchNBT);
    NbtValue.FILTER_OREDICT.setBoolean(nbtRoot, useOreDict);
    NbtValue.FILTER_STICKY.setBoolean(nbtRoot, sticky);
    NbtValue.FILTER_ADVANCED.setBoolean(nbtRoot, isAdvanced);
    NbtValue.FILTER_LIMITED.setBoolean(nbtRoot, isLimited);
    NbtValue.FILTER_DAMAGE.setInt(nbtRoot, damageMode.ordinal());

    NBTTagList tagList = new NBTTagList();
    items.apply(new Callback<ItemStack>() {
      @Override
      public void apply(@Nonnull ItemStack item) {
        NBTTagCompound itemTag = new NBTTagCompound();
        if (Prep.isValid(item)) {
          item.writeToNBT(itemTag);
          tagList.appendTag(itemTag);
        }
      }
    });
    nbtRoot.setTag("items", tagList);
  }

  // @Override
  // @SideOnly(Side.CLIENT)
  // public IItemFilterGui getGui(GuiExternalConnection gui, IItemConduit itemConduit, boolean isInput) {
  // ItemConduitFilterContainer cont = new ItemConduitFilterContainer(itemConduit, gui.getDir(), isInput);
  // BasicItemFilterGui basicItemFilterGui = new BasicItemFilterGui(gui, cont, !isInput);
  // basicItemFilterGui.createFilterSlots();
  // return basicItemFilterGui;
  // }

  @Override
  public void readFromNBT(@Nonnull NBTTagCompound nbtRoot) {
    isBlacklist = NbtValue.FILTER_BLACKLIST.getBoolean(nbtRoot);
    matchMeta = NbtValue.FILTER_META.getBoolean(nbtRoot);
    matchNBT = NbtValue.FILTER_NBT.getBoolean(nbtRoot);
    useOreDict = NbtValue.FILTER_OREDICT.getBoolean(nbtRoot);
    sticky = NbtValue.FILTER_STICKY.getBoolean(nbtRoot);
    isAdvanced = NbtValue.FILTER_ADVANCED.getBoolean(nbtRoot);
    isLimited = NbtValue.FILTER_LIMITED.getBoolean(nbtRoot);
    damageMode = NullHelper.notnullJ(DamageMode.values()[NbtValue.FILTER_DAMAGE.getInt(nbtRoot) & 255], "Enum.values()");

    items.clear();
    NBTTagList tagList = nbtRoot.getTagList("items", nbtRoot.getId());
    for (int i = 0; i < tagList.tagCount(); i++) {
      items.add(new ItemStack(tagList.getCompoundTagAt(i)));
    }

    int numItems = isAdvanced ? 10 : 5;
    while (items.size() < numItems) {
      items.add(Prep.getEmpty());
    }

    oreIds.clear();
    for (int i = 0; i < numItems; i++) {
      oreIds.add(null);
    }
  }

  @Override
  public void writeToByteBuf(@Nonnull ByteBuf buf) {
    NBTTagCompound root = new NBTTagCompound();
    writeToNBT(root);
    NetworkUtil.writeNBTTagCompound(root, buf);
  }

  @Override
  public void readFromByteBuf(@Nonnull ByteBuf buf) {
    NBTTagCompound tag = NetworkUtil.readNBTTagCompound(buf);
    readFromNBT(tag);
  }

  @Override
  public int getSizeInventory() {
    return items.size();
  }

  @Override
  public @Nonnull ItemStack getStackInSlot(int i) {
    if (i < 0 || i >= items.size()) {
      return Prep.getEmpty();
    }
    return items.get(i);
  }

  @Override
  public @Nonnull ItemStack decrStackSize(int index, int amount) {
    if (index < 0 || index >= items.size()) {
      return Prep.getEmpty();
    }
    oreIds.set(index, null);
    return items.get(index).splitStack(amount);
  }

  @Override
  public void setInventorySlotContents(int index, @Nonnull ItemStack itemstack) {
    if (index < 0 || index >= items.size()) {
      return;
    }
    ItemStack copy = itemstack.copy();
    copy.setCount(1);
    items.set(index, copy);
    oreIds.set(index, null);
  }

  @Override
  public @Nonnull ItemStack removeStackFromSlot(int index) {
    if (index < 0 || index >= items.size()) {
      return Prep.getEmpty();
    }
    ItemStack res = items.get(index);
    items.set(index, Prep.getEmpty());
    return res;
  }

  @Override
  public void clear() {
    for (int index = 0; index < items.size(); index++) {
      items.set(index, Prep.getEmpty());
    }
  }

  @Override
  public @Nonnull String getName() {
    return "Item Filter";
  }

  @Override
  public int getInventoryStackLimit() {
    return 1;
  }

  @Override
  public boolean hasCustomName() {
    return false;
  }

  @Override
  public void markDirty() {
  }

  @Override
  public void openInventory(@Nonnull EntityPlayer e) {
  }

  @Override
  public void closeInventory(@Nonnull EntityPlayer e) {
  }

  @Override
  public boolean isItemValidForSlot(int i, @Nonnull ItemStack itemstack) {
    return true;
  }

  @Override
  public void createGhostSlots(@Nonnull NNList<GhostSlot> slots, int xOffset, int yOffset, @Nullable Runnable cb) {
    int topY = yOffset;
    int leftX = xOffset;
    int index = 0;
    int numRows = isAdvanced ? 2 : 1;
    for (int row = 0; row < numRows; ++row) {
      for (int col = 0; col < 5; ++col) {
        int x = leftX + col * 18;
        int y = topY + row * 20;
        slots.add(new ItemFilterGhostSlot(index, x, y, cb));
        index++;
      }
    }
  }

  @Override
  public int getSlotCount() {
    return getSizeInventory();
  }

  public boolean isAdvanced() {
    return isAdvanced;
  }

  @Override
  public boolean isLimited() {
    return isLimited;
  }

  public boolean isDefault() {
    return !isAdvanced && !isValid() && isBlacklist == DEFAULT_BLACKLIST && matchMeta == DEFAULT_META && matchNBT == DEFAULT_MBT
        && useOreDict == DEFAULT_ORE_DICT && sticky == DEFAULT_STICKY;
  }

  @Override
  public String toString() {
    // return "ItemFilter [isBlacklist=" + isBlacklist + ", matchMeta=" + matchMeta + ", matchNBT=" + matchNBT + ", useOreDict=" + useOreDict + ", sticky="
    // + sticky + ", items=" + Arrays.toString(items) + ", oreIds=" + Arrays.toString(oreIds) + ", isAdvanced=" + isAdvanced + "]";
    return "ItemFilter [isAdvanced=" + isAdvanced + ", items=" + items + "]";
  }

  class ItemFilterGhostSlot extends GhostSlot {
    private final Runnable cb;

    ItemFilterGhostSlot(int slot, int x, int y, Runnable cb) {
      this.setX(x);
      this.setY(y);
      this.setSlot(slot);
      this.cb = cb;
      this.setDisplayStdOverlay(isLimited);
      this.setStackSizeLimit(isLimited ? 64 * 3 : 1);
    }

    @Override
    public void putStack(@Nonnull ItemStack stack, int realsize) {
      if (Prep.isValid(stack)) {
        stack = stack.copy();
        stack.setCount(MathHelper.clamp(realsize, 1, getStackSizeLimit()));
      }
      items.set(getSlot(), stack);
      cb.run();
    }

    @Override
    public @Nonnull ItemStack getStack() {
      return items.get(getSlot());
    }
  }

  @Override
  public @Nonnull ITextComponent getDisplayName() {
    return hasCustomName() ? new TextComponentString(getName()) : new TextComponentTranslation(getName(), new Object[0]);
  }

  @Override
  public int getField(int id) {
    return 0;
  }

  @Override
  public void setField(int id, int value) {
  }

  @Override
  public int getFieldCount() {
    return 0;
  }

  public static class FilterResult {

    static final FilterResult PASS = new FilterResult(true);
    static final FilterResult FAIL = new FilterResult(false);

    private final boolean pass;
    private final int limit;

    /**
     * Creates a pass/fail result without stack size limit.
     */
    private FilterResult(boolean pass) {
      this.pass = pass;
      this.limit = -1;
    }

    /**
     * Creates a pass result with the given stack size limit.
     */
    public FilterResult(int limit) {
      this.pass = true;
      this.limit = limit;
    }

    /**
     * @return true if the item passes the filter
     */
    public boolean isPass() {
      return pass;
    }

    /**
     * @return the result of isPass() but inverted if the invert parameter is true
     */
    public boolean isPass(boolean invert) {
      return pass != invert;
    }

    public int getLimit() {
      return limit;
    }

    /**
     * @return true if the item passes the filter and it matched a rule that gave a stack size limit
     */
    public boolean hasLimit() {
      return pass && limit >= 0;
    }
  }

  @Override
  public boolean isEmpty() {
    return false;
  }

  @Override
  public boolean isUsableByPlayer(@Nonnull EntityPlayer player) {
    return true;
  }

  @Override
  public IItemFilterGui getGui(@Nonnull GuiContainerBaseEIO gui, @Nonnull IItemFilterContainer filterContainer, boolean isStickyModeAvailable) {
    // TODO Auto-generated method stub
    return null;
  }

  public void openGui(@Nonnull EntityPlayer player, @Nonnull ItemStack filter, @Nonnull World worldIn, @Nonnull BlockPos pos, @Nonnull EnumFacing dir,
      int param1) {
    ModObjectRegistry.getModObjectNN(filter.getItem()).openGui(worldIn, pos, player, dir, param1);
  }
}
