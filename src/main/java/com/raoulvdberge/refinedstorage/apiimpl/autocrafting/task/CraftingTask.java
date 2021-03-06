package com.raoulvdberge.refinedstorage.apiimpl.autocrafting.task;

import com.raoulvdberge.refinedstorage.api.autocrafting.*;
import com.raoulvdberge.refinedstorage.api.autocrafting.craftingmonitor.ICraftingMonitorElement;
import com.raoulvdberge.refinedstorage.api.autocrafting.craftingmonitor.ICraftingMonitorElementList;
import com.raoulvdberge.refinedstorage.api.autocrafting.preview.ICraftingPreviewElement;
import com.raoulvdberge.refinedstorage.api.autocrafting.task.*;
import com.raoulvdberge.refinedstorage.api.network.INetwork;
import com.raoulvdberge.refinedstorage.api.network.node.INetworkNode;
import com.raoulvdberge.refinedstorage.api.util.IComparer;
import com.raoulvdberge.refinedstorage.api.util.IStackList;
import com.raoulvdberge.refinedstorage.apiimpl.API;
import com.raoulvdberge.refinedstorage.apiimpl.autocrafting.craftingmonitor.CraftingMonitorElementColor;
import com.raoulvdberge.refinedstorage.apiimpl.autocrafting.craftingmonitor.CraftingMonitorElementFluidRender;
import com.raoulvdberge.refinedstorage.apiimpl.autocrafting.craftingmonitor.CraftingMonitorElementItemRender;
import com.raoulvdberge.refinedstorage.apiimpl.autocrafting.craftingmonitor.CraftingMonitorElementText;
import com.raoulvdberge.refinedstorage.apiimpl.autocrafting.preview.CraftingPreviewElementFluidStack;
import com.raoulvdberge.refinedstorage.apiimpl.autocrafting.preview.CraftingPreviewElementItemStack;
import com.raoulvdberge.refinedstorage.apiimpl.autocrafting.task.extractor.CraftingExtractor;
import com.raoulvdberge.refinedstorage.apiimpl.autocrafting.task.extractor.CraftingExtractorStack;
import com.raoulvdberge.refinedstorage.apiimpl.autocrafting.task.extractor.CraftingExtractorStatus;
import com.raoulvdberge.refinedstorage.apiimpl.autocrafting.task.inserter.CraftingInserter;
import com.raoulvdberge.refinedstorage.apiimpl.autocrafting.task.inserter.CraftingInserterItem;
import com.raoulvdberge.refinedstorage.apiimpl.autocrafting.task.inserter.CraftingInserterItemStatus;
import com.raoulvdberge.refinedstorage.apiimpl.autocrafting.task.step.CraftingStep;
import com.raoulvdberge.refinedstorage.apiimpl.autocrafting.task.step.CraftingStepCraft;
import com.raoulvdberge.refinedstorage.apiimpl.autocrafting.task.step.CraftingStepProcess;
import com.raoulvdberge.refinedstorage.util.StackUtils;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.items.ItemHandlerHelper;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nullable;
import java.util.*;

public class CraftingTask implements ICraftingTask {
    private static final long CALCULATION_TIMEOUT_MS = 5000;

    private static final String NBT_REQUESTED = "Requested";
    private static final String NBT_QUANTITY = "Quantity";
    private static final String NBT_PATTERN = "Pattern";
    private static final String NBT_STEPS = "Steps";
    private static final String NBT_INSERTER = "Inserter";
    private static final String NBT_TICKS = "Ticks";
    private static final String NBT_ID = "Id";
    private static final String NBT_MISSING = "Missing";
    private static final String NBT_EXECUTION_STARTED = "ExecutionStarted";

    private static final String NBT_PATTERN_STACK = "Stack";
    private static final String NBT_PATTERN_CONTAINER_POS = "ContainerPos";

    private INetwork network;
    private ICraftingRequestInfo requested;
    private int quantity;
    private ICraftingPattern pattern;
    private List<CraftingStep> steps = new LinkedList<>();
    private CraftingInserter inserter;
    private Set<ICraftingPattern> patternsUsed = new HashSet<>();
    private int ticks = 0;
    private long calculationStarted = -1;
    private long executionStarted = -1;
    private UUID id = UUID.randomUUID();

    private IStackList<ItemStack> toTake = API.instance().createItemStackList();
    private IStackList<FluidStack> toTakeFluids = API.instance().createFluidStackList();

    private IStackList<ItemStack> missing = API.instance().createItemStackList();
    private IStackList<FluidStack> missingFluids = API.instance().createFluidStackList();

    private IStackList<ItemStack> toCraft = API.instance().createItemStackList();
    private IStackList<FluidStack> toCraftFluids = API.instance().createFluidStackList();

    public CraftingTask(INetwork network, ICraftingRequestInfo requested, int quantity, ICraftingPattern pattern) {
        this.network = network;
        this.inserter = new CraftingInserter(network);
        this.requested = requested;
        this.quantity = quantity;
        this.pattern = pattern;
    }

    public CraftingTask(INetwork network, NBTTagCompound tag) throws CraftingTaskReadException {
        this.network = network;

        this.requested = API.instance().createCraftingRequestInfo(tag.getCompoundTag(NBT_REQUESTED));
        this.quantity = tag.getInteger(NBT_QUANTITY);
        this.pattern = readPatternFromNbt(tag.getCompoundTag(NBT_PATTERN), network.world());
        this.inserter = new CraftingInserter(network, tag.getTagList(NBT_INSERTER, Constants.NBT.TAG_COMPOUND));
        this.ticks = tag.getInteger(NBT_TICKS);
        this.id = tag.getUniqueId(NBT_ID);

        NBTTagList steps = tag.getTagList(NBT_STEPS, Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < steps.tagCount(); ++i) {
            NBTTagCompound stepTag = steps.getCompoundTagAt(i);

            this.steps.add(CraftingStep.readFromNbt(network, inserter, stepTag));
        }

        NBTTagList missing = tag.getTagList(NBT_MISSING, Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < missing.tagCount(); ++i) {
            ItemStack missingItem = StackUtils.deserializeStackFromNbt(missing.getCompoundTagAt(i));

            if (missingItem.isEmpty()) {
                throw new CraftingTaskReadException("Missing item is empty");
            }

            this.missing.add(missingItem);
        }

        if (tag.hasKey(NBT_EXECUTION_STARTED)) {
            this.executionStarted = tag.getLong(NBT_EXECUTION_STARTED);
        }
    }

    @Override
    @Nullable
    public ICraftingTaskError calculate() {
        if (calculationStarted != -1) {
            throw new IllegalStateException("Task already calculated!");
        }

        if (executionStarted != -1) {
            throw new IllegalStateException("Task already started!");
        }

        this.calculationStarted = System.currentTimeMillis();

        int qty = this.quantity;
        int qtyPerCraft = getQuantityPerCraft();
        int crafted = 0;

        IStackList<ItemStack> results = API.instance().createItemStackList();
        IStackList<FluidStack> fluidResults = API.instance().createFluidStackList();

        IStackList<ItemStack> storage = network.getItemStorageCache().getList().copy();
        IStackList<FluidStack> fluidStorage = network.getFluidStorageCache().getList().copy();

        // Items that are being handled in other tasks aren't available to us.
        for (ICraftingTask task : network.getCraftingManager().getTasks()) {
            if (task instanceof CraftingTask) {
                for (CraftingStep step : ((CraftingTask) task).steps) {
                    CraftingExtractor extractor = null;

                    if (step instanceof CraftingStepCraft) {
                        extractor = ((CraftingStepCraft) step).getExtractor();
                    } else if (step instanceof CraftingStepProcess) {
                        extractor = ((CraftingStepProcess) step).getExtractor();
                    }

                    if (extractor != null) {
                        for (CraftingExtractorStack inUse : extractor.getStacks()) {
                            ItemStack inUseItem = inUse.getItem();

                            if (inUseItem != null) {
                                storage.remove(inUseItem);
                            } else {
                                FluidStack inUseFluid = inUse.getFluid();

                                if (inUseFluid != null) {
                                    fluidStorage.remove(inUseFluid);
                                } else {
                                    throw new IllegalStateException("Extractor stack is neither a fluid or an item!");
                                }
                            }
                        }
                    }
                }
            }
        }

        ICraftingPatternChainList patternChainList = network.getCraftingManager().createPatternChainList();

        ICraftingPatternChain patternChain = patternChainList.getChain(pattern);

        while (qty > 0) {
            Pair<CraftingStep, ICraftingTaskError> result = calculateInternal(storage, fluidStorage, results, fluidResults, patternChainList, patternChain.current());

            if (result.getRight() != null) {
                return result.getRight();
            }

            this.steps.add(result.getLeft());

            qty -= qtyPerCraft;

            crafted += qtyPerCraft;

            patternChain.cycle();
        }

        if (requested.getItem() != null) {
            this.toCraft.add(requested.getItem(), crafted);
        } else {
            this.toCraftFluids.add(requested.getFluid(), crafted);
        }

        return null;
    }

    private Pair<CraftingStep, ICraftingTaskError> calculateInternal(
        IStackList<ItemStack> mutatedStorage,
        IStackList<FluidStack> mutatedFluidStorage,
        IStackList<ItemStack> results,
        IStackList<FluidStack> fluidResults,
        ICraftingPatternChainList patternChainList,
        ICraftingPattern pattern) {

        if (System.currentTimeMillis() - calculationStarted > CALCULATION_TIMEOUT_MS) {
            return Pair.of(null, new CraftingTaskError(CraftingTaskErrorType.TOO_COMPLEX));
        }

        if (!patternsUsed.add(pattern)) {
            return Pair.of(null, new CraftingTaskError(CraftingTaskErrorType.RECURSIVE, pattern));
        }

        IStackList<ItemStack> itemsToExtract = API.instance().createItemStackList();
        IStackList<FluidStack> fluidsToExtract = API.instance().createFluidStackList();

        NonNullList<ItemStack> took = NonNullList.create();

        for (NonNullList<ItemStack> possibleInputs : pattern.getInputs()) {
            if (possibleInputs.isEmpty()) {
                took.add(ItemStack.EMPTY);

                continue;
            }

            ItemStack possibleInput;

            if (possibleInputs.size() == 1) {
                possibleInput = possibleInputs.get(0);
            } else {
                NonNullList<ItemStack> sortedPossibleInputs = NonNullList.create();
                sortedPossibleInputs.addAll(possibleInputs);

                sortedPossibleInputs.sort((a, b) -> {
                    ItemStack ar = mutatedStorage.get(a);
                    ItemStack br = mutatedStorage.get(b);

                    return (br == null ? 0 : br.getCount()) - (ar == null ? 0 : ar.getCount());
                });

                sortedPossibleInputs.sort((a, b) -> {
                    ItemStack ar = results.get(a);
                    ItemStack br = results.get(b);

                    return (br == null ? 0 : br.getCount()) - (ar == null ? 0 : ar.getCount());
                });

                possibleInput = sortedPossibleInputs.get(0);
            }

            took.add(possibleInput);

            int flags = getFlags(possibleInput);

            ItemStack fromSelf = results.get(possibleInput, flags);
            ItemStack fromNetwork = mutatedStorage.get(possibleInput, flags);

            int remaining = possibleInput.getCount();

            while (remaining > 0) {
                if (fromSelf != null) {
                    int toTake = Math.min(remaining, fromSelf.getCount());

                    itemsToExtract.add(possibleInput, toTake);

                    results.remove(fromSelf, toTake);

                    remaining -= toTake;

                    took.set(took.size() - 1, ItemHandlerHelper.copyStackWithSize(fromSelf, possibleInput.getCount()));

                    fromSelf = results.get(possibleInput, flags);
                } else if (fromNetwork != null) {
                    int toTake = Math.min(remaining, fromNetwork.getCount());

                    this.toTake.add(possibleInput, toTake);

                    itemsToExtract.add(possibleInput, toTake);

                    mutatedStorage.remove(fromNetwork, toTake);

                    remaining -= toTake;

                    took.set(took.size() - 1, ItemHandlerHelper.copyStackWithSize(fromNetwork, possibleInput.getCount()));

                    fromNetwork = mutatedStorage.get(possibleInput, flags);
                } else {
                    ICraftingPattern subPattern = network.getCraftingManager().getPattern(possibleInput);

                    if (subPattern != null) {
                        ICraftingPatternChain subPatternChain = patternChainList.getChain(subPattern);

                        while ((fromSelf == null ? 0 : fromSelf.getCount()) < remaining) {
                            Pair<CraftingStep, ICraftingTaskError> result = calculateInternal(mutatedStorage, mutatedFluidStorage, results, fluidResults, patternChainList, subPatternChain.current());

                            if (result.getRight() != null) {
                                return Pair.of(null, result.getRight());
                            }

                            this.steps.add(result.getLeft());

                            fromSelf = results.get(possibleInput, flags);
                            if (fromSelf == null) {
                                throw new IllegalStateException("Recursive calculation didn't yield anything");
                            }

                            fromNetwork = mutatedStorage.get(possibleInput, flags);

                            subPatternChain.cycle();
                        }

                        // fromSelf contains the amount crafted after the loop.
                        this.toCraft.add(possibleInput, fromSelf.getCount());
                    } else {
                        this.missing.add(possibleInput, remaining);

                        itemsToExtract.add(possibleInput, remaining);

                        remaining = 0;
                    }
                }
            }
        }

        for (FluidStack input : pattern.getFluidInputs()) {
            FluidStack fromSelf = fluidResults.get(input, IComparer.COMPARE_NBT);
            FluidStack fromNetwork = mutatedFluidStorage.get(input, IComparer.COMPARE_NBT);

            int remaining = input.amount;

            while (remaining > 0) {
                if (fromSelf != null) {
                    int toTake = Math.min(remaining, fromSelf.amount);

                    fluidsToExtract.add(input, toTake);

                    fluidResults.remove(input, toTake);

                    remaining -= toTake;

                    fromSelf = fluidResults.get(input, IComparer.COMPARE_NBT);
                } else if (fromNetwork != null) {
                    int toTake = Math.min(remaining, fromNetwork.amount);

                    this.toTakeFluids.add(input, toTake);

                    fluidsToExtract.add(input, toTake);

                    mutatedFluidStorage.remove(fromNetwork, toTake);

                    remaining -= toTake;

                    fromNetwork = mutatedFluidStorage.get(input, IComparer.COMPARE_NBT);
                } else {
                    ICraftingPattern subPattern = network.getCraftingManager().getPattern(input);

                    if (subPattern != null) {
                        ICraftingPatternChain subPatternChain = patternChainList.getChain(subPattern);

                        while ((fromSelf == null ? 0 : fromSelf.amount) < remaining) {
                            Pair<CraftingStep, ICraftingTaskError> result = calculateInternal(mutatedStorage, mutatedFluidStorage, results, fluidResults, patternChainList, subPatternChain.current());

                            if (result.getRight() != null) {
                                return Pair.of(null, result.getRight());
                            }

                            this.steps.add(result.getLeft());

                            fromSelf = fluidResults.get(input, IComparer.COMPARE_NBT);
                            if (fromSelf == null) {
                                throw new IllegalStateException("Recursive fluid calculation didn't yield anything");
                            }

                            fromNetwork = mutatedFluidStorage.get(input, IComparer.COMPARE_NBT);

                            subPatternChain.cycle();
                        }

                        // fromSelf contains the amount crafted after the loop.
                        this.toCraftFluids.add(input, fromSelf.amount);
                    } else {
                        this.missingFluids.add(input, remaining);

                        fluidsToExtract.add(input, remaining);

                        remaining = 0;
                    }
                }
            }
        }

        patternsUsed.remove(pattern);

        if (pattern.isProcessing()) {
            for (ItemStack output : pattern.getOutputs()) {
                results.add(output);
            }

            for (FluidStack output : pattern.getFluidOutputs()) {
                fluidResults.add(output);
            }

            return Pair.of(new CraftingStepProcess(pattern, network, new ArrayList<>(itemsToExtract.getStacks()), new ArrayList<>(fluidsToExtract.getStacks())), null);
        } else {
            if (!fluidsToExtract.isEmpty()) {
                throw new IllegalStateException("Cannot extract fluids in normal pattern!");
            }

            results.add(pattern.getOutput(took));

            for (ItemStack byproduct : pattern.getByproducts(took)) {
                results.add(byproduct);
            }

            return Pair.of(new CraftingStepCraft(pattern, inserter, network, new ArrayList<>(itemsToExtract.getStacks()), took), null);
        }
    }

    @Override
    public int getQuantityPerCraft() {
        int qty = 0;

        if (requested.getItem() != null) {
            for (ItemStack output : pattern.getOutputs()) {
                if (API.instance().getComparer().isEqualNoQuantity(output, requested.getItem())) {
                    qty += output.getCount();

                    if (!pattern.isProcessing()) {
                        break;
                    }
                }
            }
        } else {
            for (FluidStack output : pattern.getFluidOutputs()) {
                if (API.instance().getComparer().isEqual(output, requested.getFluid(), IComparer.COMPARE_NBT)) {
                    qty += output.amount;
                }
            }
        }

        return qty;
    }

    @Override
    public boolean update() {
        if (executionStarted == -1) {
            executionStarted = System.currentTimeMillis();
        }

        boolean allCompleted = true;

        if (ticks % getTickInterval(pattern.getContainer().getSpeedUpgradeCount()) == 0) {
            inserter.insertOne();
        }

        for (CraftingStep step : steps) {
            if (!step.isCompleted()) {
                allCompleted = false;

                if (ticks % getTickInterval(step.getPattern().getContainer().getSpeedUpgradeCount()) == 0 && step.canExecute() && step.execute()) {
                    step.setCompleted();

                    network.getCraftingManager().onTaskChanged();
                }
            }
        }

        ticks++;

        return allCompleted && inserter.getItems().isEmpty();
    }

    @Override
    public void onCancelled() {
        inserter.insertAll();
    }

    @Override
    public int getQuantity() {
        return quantity;
    }

    @Override
    public ICraftingRequestInfo getRequested() {
        return requested;
    }

    @Override
    public int onTrackedInsert(ItemStack stack, int size) {
        for (CraftingStep step : steps) {
            if (step instanceof CraftingStepProcess) {
                size = ((CraftingStepProcess) step).onTrackedItemInserted(stack, size);

                if (size == 0) {
                    break;
                }
            }
        }

        return size;
    }

    @Override
    public int onTrackedInsert(FluidStack stack, int size) {
        for (CraftingStep step : steps) {
            if (step instanceof CraftingStepProcess) {
                size = ((CraftingStepProcess) step).onTrackedFluidInserted(stack, size);

                if (size == 0) {
                    break;
                }
            }
        }

        return size;
    }

    @Override
    public List<ICraftingMonitorElement> getCraftingMonitorElements() {
        ICraftingMonitorElementList elements = API.instance().createCraftingMonitorElementList();

        if (!missing.isEmpty() || !missingFluids.isEmpty()) {
            elements.directAdd(new CraftingMonitorElementText("gui.refinedstorage:crafting_monitor.missing", 5));
        }

        if (!missing.isEmpty()) {
            for (ItemStack missing : this.missing.getStacks()) {
                elements.add(new CraftingMonitorElementColor(new CraftingMonitorElementItemRender(missing, missing.getCount(), 0), "", CraftingMonitorElementColor.COLOR_ERROR));
            }

            elements.commit();
        }

        if (!missingFluids.isEmpty()) {
            for (FluidStack missing : this.missingFluids.getStacks()) {
                elements.add(new CraftingMonitorElementColor(new CraftingMonitorElementFluidRender(missing, missing.amount, 0), "", CraftingMonitorElementColor.COLOR_ERROR));
            }

            elements.commit();
        }

        if (!inserter.getItems().isEmpty()) {
            elements.directAdd(new CraftingMonitorElementText("gui.refinedstorage:crafting_monitor.items_inserting", 5));

            for (CraftingInserterItem item : inserter.getItems()) {
                ICraftingMonitorElement element = new CraftingMonitorElementItemRender(item.getStack(), item.getStack().getCount(), 0);

                if (item.getStatus() == CraftingInserterItemStatus.FULL) {
                    element = new CraftingMonitorElementColor(element, "gui.refinedstorage:crafting_monitor.network_full", CraftingMonitorElementColor.COLOR_ERROR);
                }

                elements.add(element);
            }

            elements.commit();
        }

        if (steps.stream().anyMatch(s -> s instanceof CraftingStepCraft && !s.isCompleted() && !((CraftingStepCraft) s).getExtractor().getStacks().isEmpty())) {
            elements.directAdd(new CraftingMonitorElementText("gui.refinedstorage:crafting_monitor.items_crafting", 5));

            for (CraftingStep step : steps) {
                if (step instanceof CraftingStepCraft && !step.isCompleted()) {
                    CraftingExtractor extractor = ((CraftingStepCraft) step).getExtractor();

                    for (int i = 0; i < extractor.getStacks().size(); ++i) {
                        // Assume we have an item here.
                        CraftingExtractorStack stack = extractor.getStacks().get(i);

                        ICraftingMonitorElement element = new CraftingMonitorElementItemRender(stack.getItem(), stack.getItem().getCount(), 0);

                        if (stack.getStatus() == CraftingExtractorStatus.MISSING) {
                            element = new CraftingMonitorElementColor(element, "gui.refinedstorage:crafting_monitor.waiting_for_items", CraftingMonitorElementColor.COLOR_INFO);
                        }

                        elements.add(element);
                    }
                }
            }

            elements.commit();
        }

        if (steps.stream().anyMatch(s -> s instanceof CraftingStepProcess && !s.isCompleted())) {
            elements.directAdd(new CraftingMonitorElementText("gui.refinedstorage:crafting_monitor.processing", 5));

            for (CraftingStep step : steps) {
                if (step instanceof CraftingStepProcess && !step.isCompleted()) {
                    CraftingExtractor extractor = ((CraftingStepProcess) step).getExtractor();

                    for (int i = 0; i < extractor.getStacks().size(); ++i) {
                        CraftingExtractorStack stack = extractor.getStacks().get(i);

                        if (stack.getItem() == null) {
                            continue;
                        }

                        elements.add(wrapAccordingToStatus(new CraftingMonitorElementItemRender(stack.getItem(), stack.getItem().getCount(), 0), stack));
                    }
                }
            }

            elements.commit();

            for (CraftingStep step : steps) {
                if (step instanceof CraftingStepProcess && !step.isCompleted()) {
                    CraftingExtractor extractor = ((CraftingStepProcess) step).getExtractor();

                    for (int i = 0; i < extractor.getStacks().size(); ++i) {
                        CraftingExtractorStack stack = extractor.getStacks().get(i);

                        if (stack.getItem() != null) {
                            continue;
                        }

                        elements.add(wrapAccordingToStatus(new CraftingMonitorElementFluidRender(stack.getFluid(), stack.getFluid().amount, 0), stack));
                    }
                }
            }

            elements.commit();
        }

        return elements.getElements();
    }

    private ICraftingMonitorElement wrapAccordingToStatus(ICraftingMonitorElement element, CraftingExtractorStack stack) {
        if (stack.getStatus() == CraftingExtractorStatus.MISSING) {
            element = new CraftingMonitorElementColor(element, stack.getFluid() != null ? "gui.refinedstorage:crafting_monitor.waiting_for_fluids" : "gui.refinedstorage:crafting_monitor.waiting_for_items", CraftingMonitorElementColor.COLOR_INFO);
        } else if (stack.getStatus() == CraftingExtractorStatus.MACHINE_DOES_NOT_ACCEPT) {
            element = new CraftingMonitorElementColor(element, stack.getFluid() != null ? "gui.refinedstorage:crafting_monitor.machine_does_not_accept_fluid" : "gui.refinedstorage:crafting_monitor.machine_does_not_accept_item", CraftingMonitorElementColor.COLOR_ERROR);
        } else if (stack.getStatus() == CraftingExtractorStatus.MACHINE_NONE) {
            element = new CraftingMonitorElementColor(element, "gui.refinedstorage:crafting_monitor.machine_none", CraftingMonitorElementColor.COLOR_ERROR);
        } else if (stack.getStatus() == CraftingExtractorStatus.EXTRACTED) {
            element = new CraftingMonitorElementColor(element, "gui.refinedstorage:crafting_monitor.inserted_into_machine", CraftingMonitorElementColor.COLOR_SUCCESS);
        }

        return element;
    }

    @Override
    public List<ICraftingPreviewElement> getPreviewStacks() {
        Map<Integer, CraftingPreviewElementItemStack> map = new LinkedHashMap<>();
        Map<Integer, CraftingPreviewElementFluidStack> mapFluids = new LinkedHashMap<>();

        for (ItemStack stack : toCraft.getStacks()) {
            int hash = API.instance().getItemStackHashCode(stack);

            CraftingPreviewElementItemStack previewStack = map.get(hash);

            if (previewStack == null) {
                previewStack = new CraftingPreviewElementItemStack(stack);
            }

            previewStack.addToCraft(stack.getCount());

            map.put(hash, previewStack);
        }

        for (FluidStack stack : toCraftFluids.getStacks()) {
            int hash = API.instance().getFluidStackHashCode(stack);

            CraftingPreviewElementFluidStack previewStack = mapFluids.get(hash);

            if (previewStack == null) {
                previewStack = new CraftingPreviewElementFluidStack(stack);
            }

            previewStack.addToCraft(stack.amount);

            mapFluids.put(hash, previewStack);
        }

        for (ItemStack stack : missing.getStacks()) {
            int hash = API.instance().getItemStackHashCode(stack);

            CraftingPreviewElementItemStack previewStack = map.get(hash);

            if (previewStack == null) {
                previewStack = new CraftingPreviewElementItemStack(stack);
            }

            previewStack.setMissing(true);
            previewStack.addToCraft(stack.getCount());

            map.put(hash, previewStack);
        }

        for (FluidStack stack : missingFluids.getStacks()) {
            int hash = API.instance().getFluidStackHashCode(stack);

            CraftingPreviewElementFluidStack previewStack = mapFluids.get(hash);

            if (previewStack == null) {
                previewStack = new CraftingPreviewElementFluidStack(stack);
            }

            previewStack.setMissing(true);
            previewStack.addToCraft(stack.amount);

            mapFluids.put(hash, previewStack);
        }

        for (ItemStack stack : toTake.getStacks()) {
            int hash = API.instance().getItemStackHashCode(stack);

            CraftingPreviewElementItemStack previewStack = map.get(hash);

            if (previewStack == null) {
                previewStack = new CraftingPreviewElementItemStack(stack);
            }

            previewStack.addAvailable(stack.getCount());

            map.put(hash, previewStack);
        }

        for (FluidStack stack : toTakeFluids.getStacks()) {
            int hash = API.instance().getFluidStackHashCode(stack);

            CraftingPreviewElementFluidStack previewStack = mapFluids.get(hash);

            if (previewStack == null) {
                previewStack = new CraftingPreviewElementFluidStack(stack);
            }

            previewStack.addAvailable(stack.amount);

            mapFluids.put(hash, previewStack);
        }

        List<ICraftingPreviewElement> elements = new ArrayList<>();

        elements.addAll(map.values());
        elements.addAll(mapFluids.values());

        return elements;
    }

    @Override
    public ICraftingPattern getPattern() {
        return pattern;
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public long getExecutionStarted() {
        return executionStarted;
    }

    @Override
    public IStackList<ItemStack> getMissing() {
        return missing;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public NBTTagCompound writeToNbt(NBTTagCompound tag) {
        tag.setTag(NBT_REQUESTED, requested.writeToNbt());
        tag.setInteger(NBT_QUANTITY, quantity);
        tag.setTag(NBT_PATTERN, writePatternToNbt(pattern));
        tag.setTag(NBT_INSERTER, inserter.writeToNbt());
        tag.setInteger(NBT_TICKS, ticks);
        tag.setUniqueId(NBT_ID, id);
        tag.setLong(NBT_EXECUTION_STARTED, executionStarted);

        NBTTagList steps = new NBTTagList();
        for (CraftingStep step : this.steps) {
            steps.appendTag(step.writeToNbt());
        }

        tag.setTag(NBT_STEPS, steps);

        NBTTagList missing = new NBTTagList();
        for (ItemStack missingItem : this.missing.getStacks()) {
            missing.appendTag(StackUtils.serializeStackToNbt(missingItem));
        }

        tag.setTag(NBT_MISSING, missing);

        return tag;
    }

    private int getTickInterval(int speedUpgrades) {
        switch (speedUpgrades) {
            case 0:
                return 10;
            case 1:
                return 8;
            case 2:
                return 6;
            case 3:
                return 4;
            case 4:
                return 2;
            default:
                return 2;
        }
    }

    public static NBTTagCompound writePatternToNbt(ICraftingPattern pattern) {
        NBTTagCompound tag = new NBTTagCompound();

        tag.setTag(NBT_PATTERN_STACK, pattern.getStack().serializeNBT());
        tag.setLong(NBT_PATTERN_CONTAINER_POS, pattern.getContainer().getPosition().toLong());

        return tag;
    }

    public static ICraftingPattern readPatternFromNbt(NBTTagCompound tag, World world) throws CraftingTaskReadException {
        BlockPos containerPos = BlockPos.fromLong(tag.getLong(NBT_PATTERN_CONTAINER_POS));

        INetworkNode node = API.instance().getNetworkNodeManager(world).getNode(containerPos);

        if (node instanceof ICraftingPatternContainer) {
            ItemStack stack = new ItemStack(tag.getCompoundTag(NBT_PATTERN_STACK));

            if (stack.getItem() instanceof ICraftingPatternProvider) {
                return ((ICraftingPatternProvider) stack.getItem()).create(world, stack, (ICraftingPatternContainer) node);
            } else {
                throw new CraftingTaskReadException("Pattern stack is not a crafting pattern provider");
            }
        } else {
            throw new CraftingTaskReadException("Crafting pattern container doesn't exist anymore");
        }
    }

    public static int getFlags(ItemStack stack) {
        if (stack.getItem().isDamageable()) {
            return IComparer.COMPARE_NBT;
        }

        return IComparer.COMPARE_NBT | IComparer.COMPARE_DAMAGE;
    }
}
