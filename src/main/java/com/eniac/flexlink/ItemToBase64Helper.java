package com.eniac.flexlink;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.io.IOException;
import java.util.Base64;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
public class ItemToBase64Helper {

    public static void main(String[] args) {
        new ItemToBase64Helper(Minecraft.getInstance().player.getMainHandItem()).toBase64();
    }


    public FrameHelper frameHelper1;
    public String base64;

    public ItemToBase64Helper(ItemStack itemStack) {
        this.frameHelper1 = new FrameHelper(54, itemStack);

        try (NativeImage image = fromFrame(this.frameHelper1.framebuffer)) {
            this.base64 = Base64.getEncoder().encodeToString(image.asByteArray());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public NativeImage fromFrame(RenderTarget frame) {
        NativeImage img = new NativeImage(frame.width, frame.height, false);
        RenderSystem.bindTexture(frame.getColorTextureId());
        img.downloadTexture(0, false);
        img.flipY();
        return img;
    }

    public String toBase64() {
        return this.base64;
    }

    public static class FrameHelper {
        public RenderTarget framebuffer;
        private PoseStack modelStack;

        public FrameHelper(int size, ItemStack itemStack) {
            this.framebuffer = new TextureTarget(size, size, true, Minecraft.ON_OSX);
            this.startRecord();
            ItemRenderer renderer = Minecraft.getInstance().getItemRenderer();
            this.renderGuiItemIcon(itemStack, 0, 0, renderer);
            this.endRecord();
        }

        public void startRecord() {
            this.modelStack = RenderSystem.getModelViewStack();
            this.modelStack.pushPose();
            this.modelStack.setIdentity();

            RenderSystem.backupProjectionMatrix();
            Matrix4f p = new Matrix4f().setOrtho(0, 16, 16, 0, -150, 150);
            RenderSystem.setProjectionMatrix(p, VertexSorting.ORTHOGRAPHIC_Z);

            this.framebuffer.bindWrite(true);
            this.framebuffer.bindRead();
        }

        public void endRecord() {
            RenderSystem.restoreProjectionMatrix();
            this.modelStack.popPose();

            this.framebuffer.unbindWrite();
            this.framebuffer.unbindRead();
        }

        public void renderGuiItemIcon(ItemStack stack, int x, int y, ItemRenderer renderer) {
            this.renderGuiItemModel(stack, x, y, renderer.getModel(stack, null, null, 0), renderer);
        }

        protected void renderGuiItemModel(ItemStack stack, int x, int y, BakedModel model, ItemRenderer renderer) {
            RenderSystem.setShaderTexture(0, InventoryMenu.BLOCK_ATLAS);
            RenderSystem.enableBlend();
            RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            PoseStack matrixStack = RenderSystem.getModelViewStack();
            matrixStack.pushPose();
            matrixStack.translate(x, y, 100.0F);
            matrixStack.translate(8.0D, 8.0D, 0.0D);
            matrixStack.scale(1.0F, -1.0F, 1.0F);
            matrixStack.scale(16.0F, 16.0F, 16.0F);
            RenderSystem.applyModelViewMatrix();
            PoseStack matrixStack2 = new PoseStack();
            MultiBufferSource.BufferSource immediate = Minecraft.getInstance().renderBuffers().bufferSource();
            boolean bl = !model.usesBlockLight();
            if (bl) {
                Lighting.setupForFlatItems();
            }

            renderer.render(stack, ItemDisplayContext.GUI, false, matrixStack2, immediate, 15728880, OverlayTexture.NO_OVERLAY, model);
            immediate.endBatch();
            RenderSystem.enableDepthTest();
            if (bl) {
                Lighting.setupFor3DItems();
            }

            matrixStack.popPose();
            RenderSystem.applyModelViewMatrix();
        }
    }
}
