/*
 * Copyright (c) 2014-2023 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.stream.Collectors;

import net.wurstclient.settings.*;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.block.Block;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferBuilder.BuiltBuffer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.dimension.DimensionType;
import net.wurstclient.Category;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.*;

public final class SearchHack extends Hack
	implements UpdateListener, PacketInputListener, RenderListener
{

	private final BlockListSetting blocks = new BlockListSetting("Blocks",
			"A list of blocks to search for.",
			"minecraft:ancient_debris", "minecraft:chest",
			"minecraft:clay", "minecraft:coal_block",
			"minecraft:deepslate_diamond_ore",
			"minecraft:deepslate_lapis_ore",
			"minecraft:diamond_block",
			"minecraft:diamond_ore",
			"minecraft:enchanting_table", "minecraft:end_portal",
			"minecraft:ender_chest", "minecraft:lapis_block",
			"minecraft:lapis_ore");
	
	private final ChunkAreaSetting area = new ChunkAreaSetting("Area",
		"The area around the player to search in.\n"
			+ "Higher values require a faster computer.");
	
	private final SliderSetting limit = new SliderSetting("Limit",
		"The maximum number of blocks to display.\n"
			+ "Higher values require a faster computer.",
		4, 3, 6, 1, ValueDisplay.LOGARITHMIC);

	private final CheckboxSetting onlyExposed = new CheckboxSetting(
			"Only show exposed",
			"Only shows ores/blocks that would be visible in caves. This can help against"
					+ " anti-X-Ray plugins.\n\n"
					+ "Remember to restart Search when changing this setting.",
			false);

	private int prevLimit;
	private boolean notify;
	
	private final HashMap<ChunkPos, ExposedBlockChunkSearcher> searchers = new HashMap<>();
	private final Set<ChunkPos> chunksToUpdate =
		Collections.synchronizedSet(new HashSet<>());
	private ExecutorService threadPool;
	
	private ForkJoinPool forkJoinPool;
	private ForkJoinTask<HashSet<BlockPos>> getMatchingBlocksTask;
	private ForkJoinTask<ArrayList<int[]>> compileVerticesTask;
	
	private VertexBuffer vertexBuffer;
	private boolean bufferUpToDate;
	
	public SearchHack()
	{
		super("Search");
		setCategory(Category.RENDER);
		addSetting(blocks);
		addSetting(area);
		addSetting(limit);
		addSetting(onlyExposed);
	}
	
	@Override
	public String getRenderName()
	{
		return getName() + " [" + blocks.getBlockNames().size()
			+ "]";
	}
	
	@Override
	public void onEnable()
	{
		prevLimit = limit.getValueI();
		notify = true;
		
		threadPool = MinPriorityThreadFactory.newFixedThreadPool();
		forkJoinPool = new ForkJoinPool();
		
		bufferUpToDate = false;
		
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(PacketInputListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	public void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(PacketInputListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		
		stopBuildingBuffer();
		threadPool.shutdownNow();
		forkJoinPool.shutdownNow();
		
		if(vertexBuffer != null)
		{
			vertexBuffer.close();
			vertexBuffer = null;
		}
		
		chunksToUpdate.clear();
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		ChunkPos chunkPos = ChunkUtils.getAffectedChunk(event.getPacket());
		
		if(chunkPos != null)
			chunksToUpdate.add(chunkPos);
	}
	
	@Override
	public void onUpdate()
	{
		DimensionType dimension = MC.world.getDimension();
		HashSet<ChunkPos> chunkUpdates = clearChunksToUpdate();
		boolean searchersChanged = false;
		
		// remove outdated ChunkSearchers
		for(ExposedBlockChunkSearcher searcher : new ArrayList<>(searchers.values()))
		{
			boolean remove = false;
			ChunkPos searcherPos = searcher.getPos();
			
			// wrong block
			if(!blocks.getBlockNames().equals(searcher.getBlockNames()))
				remove = true;
			
			// wrong dimension
			else if(dimension != searcher.getDimension())
				remove = true;
			
			// out of range
			else if(!area.isInRange(searcherPos))
				remove = true;
			
			// chunk update
			else if(chunkUpdates.contains(searcherPos))
				remove = true;
			
			if(remove)
			{
				searchers.remove(searcherPos);
				searcher.cancelSearching();
				searchersChanged = true;
			}
		}
		
		// add new ChunkSearchers
		for(Chunk chunk : area.getChunksInRange())
		{
			ChunkPos chunkPos = chunk.getPos();
			if(searchers.containsKey(chunkPos))
				continue;
			
			ExposedBlockChunkSearcher searcher =
				new ExposedBlockChunkSearcher(chunk, blocks.getBlockNames(), dimension);
			searchers.put(chunkPos, searcher);
			searcher.startSearching(threadPool);
			searchersChanged = true;
		}
		
		if(searchersChanged)
			stopBuildingBuffer();
		
		if(!areAllChunkSearchersDone())
			return;
		
		// check if limit has changed
		if(limit.getValueI() != prevLimit)
		{
			stopBuildingBuffer();
			prevLimit = limit.getValueI();
			notify = true;
		}
		
		// build the buffer
		
		if(getMatchingBlocksTask == null)
			startGetMatchingBlocksTask();
		
		if(!getMatchingBlocksTask.isDone())
			return;
		
		if(compileVerticesTask == null)
			startCompileVerticesTask();
		
		if(!compileVerticesTask.isDone())
			return;
		
		if(!bufferUpToDate)
			setBufferFromTask();
	}
	
	@Override
	public void onRender(MatrixStack matrixStack, float partialTicks)
	{
		// GL settings
		GL11.glEnable(GL11.GL_BLEND);
		GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
		GL11.glEnable(GL11.GL_CULL_FACE);
		GL11.glDisable(GL11.GL_DEPTH_TEST);
		
		matrixStack.push();
		RenderUtils.applyRegionalRenderOffset(matrixStack);
		
		float[] rainbow = RenderUtils.getRainbowColor();
		RenderSystem.setShaderColor(rainbow[0], rainbow[1], rainbow[2], 0.5F);
		
		RenderSystem.setShader(GameRenderer::getPositionProgram);
		
		if(vertexBuffer != null)
		{
			Matrix4f viewMatrix = matrixStack.peek().getPositionMatrix();
			Matrix4f projMatrix = RenderSystem.getProjectionMatrix();
			ShaderProgram shader = RenderSystem.getShader();
			vertexBuffer.bind();
			vertexBuffer.draw(viewMatrix, projMatrix, shader);
			VertexBuffer.unbind();
		}
		
		matrixStack.pop();
		
		// GL resets
		RenderSystem.setShaderColor(1, 1, 1, 1);
		GL11.glEnable(GL11.GL_DEPTH_TEST);
		GL11.glDisable(GL11.GL_BLEND);
	}
	
	private HashSet<ChunkPos> clearChunksToUpdate()
	{
		synchronized(chunksToUpdate)
		{
			HashSet<ChunkPos> chunks = new HashSet<>(chunksToUpdate);
			chunksToUpdate.clear();
			return chunks;
		}
	}
	
	private void stopBuildingBuffer()
	{
		if(getMatchingBlocksTask != null)
			getMatchingBlocksTask.cancel(true);
		getMatchingBlocksTask = null;
		
		if(compileVerticesTask != null)
			compileVerticesTask.cancel(true);
		compileVerticesTask = null;
		
		bufferUpToDate = false;
	}
	
	private boolean areAllChunkSearchersDone()
	{
		for(ExposedBlockChunkSearcher searcher : searchers.values())
			if(searcher.getStatus() != ExposedBlockChunkSearcher.Status.DONE)
				return false;
			
		return true;
	}
	
	private void startGetMatchingBlocksTask()
	{
		BlockPos eyesPos = BlockPos.ofFloored(RotationUtils.getEyesPos());
		Comparator<BlockPos> comparator =
			Comparator.comparingInt(pos -> eyesPos.getManhattanDistance(pos));
		
		getMatchingBlocksTask = forkJoinPool.submit(() -> searchers.values()
			.parallelStream().flatMap(ExposedBlockChunkSearcher::getMatchingBlocks)
			.sorted(comparator).limit(limit.getValueLog())
			.collect(Collectors.toCollection(HashSet::new)));
	}
	
	private void startCompileVerticesTask()
	{
		HashSet<BlockPos> matchingBlocks = getMatchingBlocksTask.join();
		
		if(matchingBlocks.size() < limit.getValueLog())
			notify = true;
		else if(notify)
		{
			ChatUtils.warning("Search found \u00a7lA LOT\u00a7r of blocks!"
				+ " To prevent lag, it will only show the closest \u00a76"
				+ limit.getValueString() + "\u00a7r results.");
			notify = false;
		}
		
		RegionPos region = RenderUtils.getCameraRegion();
		compileVerticesTask = forkJoinPool
			.submit(() -> BlockVertexCompiler.compile(matchingBlocks, region));
	}
	
	private void setBufferFromTask()
	{
		Tessellator tessellator = RenderSystem.renderThreadTesselator();
		BufferBuilder bufferBuilder = tessellator.getBuffer();
		bufferBuilder.begin(VertexFormat.DrawMode.QUADS,
			VertexFormats.POSITION);
		
		for(int[] vertex : compileVerticesTask.join())
			bufferBuilder.vertex(vertex[0], vertex[1], vertex[2]).next();
		
		BuiltBuffer buffer = bufferBuilder.end();
		
		if(vertexBuffer != null)
			vertexBuffer.close();
		
		vertexBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
		vertexBuffer.bind();
		vertexBuffer.upload(buffer);
		VertexBuffer.unbind();
		
		bufferUpToDate = true;
	}
}
