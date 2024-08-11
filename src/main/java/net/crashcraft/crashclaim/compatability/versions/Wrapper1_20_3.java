package net.crashcraft.crashclaim.compatability.versions;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

public class Wrapper1_20_3 extends Wrapper1_20_2 {

    private AtomicInteger ENTITY_ID;

    @Override
    public int getUniqueEntityID() {
        if (ENTITY_ID == null) {
            try {
                // https://nms.screamingsandals.org/1.19.4/net/minecraft/world/entity/Entity.html
                final Field entityCount = Class.forName("net.minecraft.world.entity.Entity").getDeclaredField("c");
                entityCount.setAccessible(true);
                ENTITY_ID = (AtomicInteger) entityCount.get(null);
            } catch (final ReflectiveOperationException e) {
                throw new IllegalArgumentException(e);
            }
        }
        return ENTITY_ID.incrementAndGet();
    }

}