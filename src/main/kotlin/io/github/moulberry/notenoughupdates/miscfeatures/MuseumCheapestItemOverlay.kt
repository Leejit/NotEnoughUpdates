/*
 * Copyright (C) 2022 NotEnoughUpdates contributors
 *
 * This file is part of NotEnoughUpdates.
 *
 * NotEnoughUpdates is free software: you can redistribute it
 * and/or modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * NotEnoughUpdates is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with NotEnoughUpdates. If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.moulberry.notenoughupdates.miscfeatures

import io.github.moulberry.notenoughupdates.NEUManager
import io.github.moulberry.notenoughupdates.NotEnoughUpdates
import io.github.moulberry.notenoughupdates.core.util.ArrowPagesUtils
import io.github.moulberry.notenoughupdates.mixins.AccessorGuiContainer
import io.github.moulberry.notenoughupdates.util.ItemResolutionQuery
import io.github.moulberry.notenoughupdates.util.Utils
import io.github.moulberry.notenoughupdates.util.stripControlCodes
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiScreen
import net.minecraft.client.gui.inventory.GuiChest
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.init.Items
import net.minecraft.inventory.Slot
import net.minecraft.item.ItemDye
import net.minecraft.util.EnumChatFormatting
import net.minecraft.util.ResourceLocation
import net.minecraftforge.client.event.GuiScreenEvent
import net.minecraftforge.client.event.GuiScreenEvent.BackgroundDrawnEvent
import net.minecraftforge.client.event.GuiScreenEvent.KeyboardInputEvent
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import org.lwjgl.input.Mouse
import org.lwjgl.opengl.GL11


object MuseumCheapestItemOverlay {
    data class MuseumItem(var name: String, var value: Double, var priceRefreshedAt: Long)

    private val backgroundResource: ResourceLocation by lazy {
        ResourceLocation("notenoughupdates:dungeon_chest_worth.png")
    }

    private const val ITEMS_PER_PAGE = 8
    private var topLeft = intArrayOf(237, 110)
    private var currentPage: Int = 0
    private var previousSlots: List<Slot> = emptyList()
    private var itemsToDonate: MutableList<MuseumItem> = emptyList<MuseumItem>().toMutableList()

    //category -> was the highest page visited?
    private var checkedPages: HashMap<String, Boolean> = hashMapOf(
        //this page only shows items when you have already donated them -> there is no useful information to gather
        "Special Items" to true,
        "Weapons" to false,
        "Armor Sets" to false,
        "Rarities" to false
    )

    @SubscribeEvent
    fun onDrawBackground(event: BackgroundDrawnEvent) {
        if (!shouldRender(event.gui)) return
        val chest = event.gui as GuiChest

        val slots = chest.inventorySlots.inventorySlots
        if (!slots.equals(previousSlots)) {
            parseItems(slots)
            checkIfHighestPageWasVisited(slots)
            sortByPrice()
        }
        previousSlots = slots

        val xSize = (event.gui as AccessorGuiContainer).xSize
        val guiLeft = (event.gui as AccessorGuiContainer).guiLeft
        val guiTop = (event.gui as AccessorGuiContainer).guiTop

        drawBackground(guiLeft, xSize, guiTop)
        drawLines(guiLeft, guiTop)
    }

    @SubscribeEvent
    fun onMouseClick(event: GuiScreenEvent.MouseInputEvent.Pre) {
        if (!shouldRender(event.gui)) return
        if (!Mouse.getEventButtonState()) return
        val guiLeft = (event.gui as AccessorGuiContainer).guiLeft
        val guiTop = (event.gui as AccessorGuiContainer).guiTop
        ArrowPagesUtils.onPageSwitchMouse(
            guiLeft, guiTop, topLeft, currentPage, totalPages()
        ) { pageChange: Int -> currentPage = pageChange }
    }


    private fun sortByPrice() {
        itemsToDonate.sortBy { it.value }
    }


    private fun drawLines(guiLeft: Int, guiTop: Int) {
        //render
        val lines = buildLines()
        lines.forEachIndexed { index, line ->
            if (index == ITEMS_PER_PAGE && !visitedAllPages()) {
                Minecraft.getMinecraft().fontRendererObj.drawStringWithShadow(
                    "${EnumChatFormatting.RED}Visit all pages for accurate info!",
                    (guiLeft + 185).toFloat(),
                    (guiTop + 85).toFloat(),
                    0
                )
            } else {
                Utils.renderAlignedString(
                    "${EnumChatFormatting.RESET}${EnumChatFormatting.BLUE}${line.name}",
                    if (line.value == Double.MAX_VALUE) "${EnumChatFormatting.RED}Unknown" else "${EnumChatFormatting.AQUA}${
                        Utils.shortNumberFormat(
                            line.value,
                            0
                        )
                    }",
                    (guiLeft + 187).toFloat(),
                    (guiTop + 5 + (index * 10)).toFloat(),
                    160
                )
            }
        }

        ArrowPagesUtils.onDraw(guiLeft, guiTop, topLeft, currentPage, totalPages())
        return
    }

    private fun buildLines(): List<MuseumItem> {
        val list = emptyList<MuseumItem>().toMutableList()
        for (i in ITEMS_PER_PAGE * currentPage..ITEMS_PER_PAGE * currentPage + ITEMS_PER_PAGE) {
            if (i >= itemsToDonate.size) {
                break
            }
            list.add(itemsToDonate[i])
        }
        return list
    }


    private fun parseItems(slots: List<Slot>) {
        //iterate upper chest with 56 slots
        val time = System.currentTimeMillis()
        for (i in 0..53) {
            val stack = slots[i].stack ?: continue
            //check for gray dye which indicates that the item has not been donated
            if (stack.item is ItemDye && stack.itemDamage == 8) {
                val name = stack.displayName.stripControlCodes()
                val internalNames = guessInternalNames(name, Utils.getOpenChestName().endsWith("Armor Sets"))
//                println("$name resolves to ${internalNames.toString()}")
                var totalValue = 0.0
                internalNames.forEach {
                    val itemValue = NotEnoughUpdates.INSTANCE.manager.auctionManager.getBazaarOrBin(it, false)
                    if (itemValue == -1.0 || itemValue == 0.0) {
                        totalValue = Double.MAX_VALUE
                        return@forEach
                    } else {
                        totalValue += itemValue
                    }
                }
                if (totalValue == 0.0) {
                    totalValue = Double.MAX_VALUE
                }

                //make sure this item does not already exist
                if (itemsToDonate.none { it.name == name }) {
                    itemsToDonate.add(MuseumItem(name, totalValue, time))
                }
            }
        }
    }

    private fun checkIfHighestPageWasVisited(slots: List<Slot>) {
        val category = getCategory()
        val nextPageSlot = slots[53]
        //if the "Next Page" arrow is missing, we are at the highest page
        if ((nextPageSlot.stack ?: return).item != Items.arrow) {
            checkedPages[category] = true
        }
    }

    @SubscribeEvent
    fun onKey(event: KeyboardInputEvent) {
        println(guessInternalNames("Mushroom Armor", true))
    }


    private fun guessInternalNames(itemName: String, armor: Boolean): List<String> {
        return if (armor) {
            val mustHaves = arrayOf(
                "HELMET",
                "LEGGINGS",
                "CHESTPLATE",
                "BOOTS"
            )
            val monochromeName = NEUManager.cleanForTitleMapSearch(itemName)
            val candidates = monochromeName.split(" ")
                .mapNotNull { NotEnoughUpdates.INSTANCE.manager.titleWordMap[it]?.keys }
                .flatten()
                .filter {
                    val item = NotEnoughUpdates.INSTANCE.manager.createItem(it)
                    val name = NEUManager.cleanForTitleMapSearch(item.displayName)
                    monochromeName.replace("armor", "") in name
                }.filter { item ->
                    mustHaves.any {
                        item.contains(it)
                    }
                }

            return candidates
        } else {
            listOf(ItemResolutionQuery.getInternalNameByDisplayName(itemName))
        }
    }


    private fun drawBackground(guiLeft: Int, xSize: Int, guiTop: Int) {
        Minecraft.getMinecraft().textureManager.bindTexture(backgroundResource)
        GL11.glColor4f(1F, 1F, 1F, 1F)
        GlStateManager.disableLighting()
        Utils.drawTexturedRect(
            guiLeft.toFloat() + xSize + 4,
            guiTop.toFloat(),
            180F,
            101F,
            0F,
            180 / 256F,
            0F,
            101 / 256F,
            GL11.GL_NEAREST
        )
    }

    private fun shouldRender(gui: GuiScreen): Boolean =
        NotEnoughUpdates.INSTANCE.config.misc.museumCheapestItemOverlay && gui is GuiChest && Utils.getOpenChestName()
            .startsWith("Museum ➜")

    private fun getCategory(): String = Utils.getOpenChestName().substring(9, Utils.getOpenChestName().length)

    private fun visitedAllPages(): Boolean = !checkedPages.containsValue(false)

    private fun totalPages(): Int = when (itemsToDonate.size % ITEMS_PER_PAGE) {
        0 -> itemsToDonate.size / ITEMS_PER_PAGE
        else -> (itemsToDonate.size / ITEMS_PER_PAGE) + 1
    }
}