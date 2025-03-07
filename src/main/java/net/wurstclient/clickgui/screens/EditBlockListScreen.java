/*
 * Copyright (c) 2014-2023 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.screens;

import java.util.List;

import org.lwjgl.glfw.GLFW;

import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.wurstclient.settings.BlockListSetting;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.ListWidget;
import net.wurstclient.util.RenderUtils;

public final class EditBlockListScreen extends Screen
{
	private final Screen prevScreen;
	private final BlockListSetting blockList;
	
	private ListGui listGui;
	private TextFieldWidget blockNameField;
	private ButtonWidget addButton;
	private ButtonWidget removeButton;
	private ButtonWidget doneButton;
	
	private Block blockToAdd;
	
	public EditBlockListScreen(Screen prevScreen, BlockListSetting blockList)
	{
		super(Text.literal(""));
		this.prevScreen = prevScreen;
		this.blockList = blockList;
	}
	
	@Override
	public void init()
	{
		listGui = new ListGui(client, this, blockList.getBlockNames());
		
		blockNameField = new TextFieldWidget(client.textRenderer,
			width / 2 - 152, height - 55, 150, 18, Text.literal(""));
		addSelectableChild(blockNameField);
		blockNameField.setMaxLength(256);
		
		addDrawableChild(
			addButton = ButtonWidget.builder(Text.literal("Add"), b -> {
				blockList.add(blockToAdd);
				blockNameField.setText("");
			}).dimensions(width / 2 - 2, height - 56, 30, 20).build());
		
		addDrawableChild(removeButton = ButtonWidget
			.builder(Text.literal("Remove Selected"),
				b -> blockList.remove(listGui.selected))
			.dimensions(width / 2 + 52, height - 56, 100, 20).build());
		
		addDrawableChild(ButtonWidget.builder(Text.literal("Reset to Defaults"),
			b -> client.setScreen(new ConfirmScreen(b2 -> {
				if(b2)
					blockList.resetToDefaults();
				client.setScreen(EditBlockListScreen.this);
			}, Text.literal("Reset to Defaults"),
				Text.literal("Are you sure?"))))
			.dimensions(width - 108, 8, 100, 20).build());
		
		addDrawableChild(doneButton = ButtonWidget
			.builder(Text.literal("Done"), b -> client.setScreen(prevScreen))
			.dimensions(width / 2 - 100, height - 28, 200, 20).build());
	}
	
	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int mouseButton)
	{
		boolean childClicked = super.mouseClicked(mouseX, mouseY, mouseButton);
		
		blockNameField.mouseClicked(mouseX, mouseY, mouseButton);
		listGui.mouseClicked(mouseX, mouseY, mouseButton);
		
		if(!childClicked && (mouseX < (width - 220) / 2
			|| mouseX > width / 2 + 129 || mouseY < 32 || mouseY > height - 64))
			listGui.selected = -1;
		
		return childClicked;
	}
	
	@Override
	public boolean mouseDragged(double double_1, double double_2, int int_1,
		double double_3, double double_4)
	{
		listGui.mouseDragged(double_1, double_2, int_1, double_3, double_4);
		return super.mouseDragged(double_1, double_2, int_1, double_3,
			double_4);
	}
	
	@Override
	public boolean mouseReleased(double double_1, double double_2, int int_1)
	{
		listGui.mouseReleased(double_1, double_2, int_1);
		return super.mouseReleased(double_1, double_2, int_1);
	}
	
	@Override
	public boolean mouseScrolled(double double_1, double double_2,
		double double_3)
	{
		listGui.mouseScrolled(double_1, double_2, double_3);
		return super.mouseScrolled(double_1, double_2, double_3);
	}
	
	@Override
	public boolean keyPressed(int keyCode, int scanCode, int int_3)
	{
		switch(keyCode)
		{
			case GLFW.GLFW_KEY_ENTER:
			if(addButton.active)
				addButton.onPress();
			break;
			
			case GLFW.GLFW_KEY_DELETE:
			if(!blockNameField.isFocused())
				removeButton.onPress();
			break;
			
			case GLFW.GLFW_KEY_ESCAPE:
			doneButton.onPress();
			break;
			
			default:
			break;
		}
		
		return super.keyPressed(keyCode, scanCode, int_3);
	}
	
	@Override
	public void tick()
	{
		blockNameField.tick();
		
		String nameOrId = blockNameField.getText();
		blockToAdd = BlockUtils.getBlockFromNameOrID(nameOrId);
		addButton.active = blockToAdd != null;
		
		removeButton.active =
			listGui.selected >= 0 && listGui.selected < listGui.list.size();
	}
	
	@Override
	public void render(DrawContext context, int mouseX, int mouseY,
		float partialTicks)
	{
		MatrixStack matrixStack = context.getMatrices();
		listGui.render(context, mouseX, mouseY, partialTicks);
		
		context.drawCenteredTextWithShadow(client.textRenderer,
			blockList.getName() + " (" + listGui.getItemCount() + ")",
			width / 2, 12, 0xffffff);
		
		matrixStack.push();
		matrixStack.translate(0, 0, 300);
		
		blockNameField.render(context, mouseX, mouseY, partialTicks);
		super.render(context, mouseX, mouseY, partialTicks);
		
		matrixStack.push();
		matrixStack.translate(-64 + width / 2 - 152, 0, 0);
		
		if(blockNameField.getText().isEmpty() && !blockNameField.isFocused())
			context.drawTextWithShadow(client.textRenderer, "block name or ID",
				68, height - 50, 0x808080);
		
		int border = blockNameField.isFocused() ? 0xffffffff : 0xffa0a0a0;
		int black = 0xff000000;
		
		context.fill(48, height - 56, 64, height - 36, border);
		context.fill(49, height - 55, 64, height - 37, black);
		context.fill(214, height - 56, 244, height - 55, border);
		context.fill(214, height - 37, 244, height - 36, border);
		context.fill(244, height - 56, 246, height - 36, border);
		context.fill(214, height - 55, 243, height - 52, black);
		context.fill(214, height - 40, 243, height - 37, black);
		context.fill(214, height - 55, 216, height - 37, black);
		context.fill(242, height - 55, 245, height - 37, black);
		
		matrixStack.pop();
		
		RenderUtils.drawItem(context,
			blockToAdd == null ? ItemStack.EMPTY : new ItemStack(blockToAdd),
			width / 2 - 164, height - 52, false);
		
		matrixStack.pop();
	}
	
	@Override
	public boolean shouldPause()
	{
		return false;
	}
	
	@Override
	public boolean shouldCloseOnEsc()
	{
		return false;
	}
	
	private static class ListGui extends ListWidget
	{
		private final MinecraftClient mc;
		private final List<String> list;
		private int selected = -1;
		
		public ListGui(MinecraftClient mc, EditBlockListScreen screen,
			List<String> list)
		{
			super(mc, screen.width, screen.height, 32, screen.height - 64, 30);
			this.mc = mc;
			this.list = list;
		}
		
		@Override
		protected int getItemCount()
		{
			return list.size();
		}
		
		@Override
		protected boolean selectItem(int index, int int_2, double var3,
			double var4)
		{
			if(index >= 0 && index < list.size())
				selected = index;
			
			return true;
		}
		
		@Override
		protected boolean isSelectedItem(int index)
		{
			return index == selected;
		}
		
		@Override
		protected void renderBackground()
		{
			
		}
		
		@Override
		protected void renderItem(DrawContext context, int index, int x, int y,
			int var4, int var5, int var6, float partialTicks)
		{
			String name = list.get(index);
			Block block = BlockUtils.getBlockFromName(name);
			ItemStack stack = new ItemStack(block);
			TextRenderer tr = mc.textRenderer;
			
			RenderUtils.drawItem(context, stack, x + 1, y + 1, true);
			String displayName = stack.isEmpty() ? "\u00a7ounknown block\u00a7r"
				: stack.getName().getString();
			context.drawText(tr, displayName, x + 28, y, 0xf0f0f0, false);
			context.drawText(tr, name, x + 28, y + 9, 0xa0a0a0, false);
			context.drawText(tr,
				"ID: " + Block.getRawIdFromState(block.getDefaultState()),
				x + 28, y + 18, 0xa0a0a0, false);
		}
	}
}
