package com.mdvcraft.mdvnpc.shop;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import java.lang.reflect.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Reflection keeps MMOItems entirely optional, without requiring private/unstable Maven artifacts. */
public final class MmoItemBridge {
    public record Identity(String type, String id) {}
    private final Logger logger;
    private boolean reported;
    private Method typeMethod, idMethod, createMethod;
    private Field instanceField;
    private void resolveApi() throws ReflectiveOperationException {
        if (typeMethod != null) return;
        var plugin = Bukkit.getPluginManager().getPlugin("MMOItems");
        if (plugin == null) throw new ClassNotFoundException("MMOItems ausente");
        Class<?> api = Class.forName("net.Indyuce.mmoitems.MMOItems", true, plugin.getClass().getClassLoader());
        Method type = api.getMethod("getTypeName", ItemStack.class);
        Method id = api.getMethod("getID", ItemStack.class);
        Method create = api.getMethod("getItem", String.class, String.class);
        Field instance = api.getField("plugin");
        typeMethod = type; idMethod = id; createMethod = create; instanceField = instance;
    }
    public MmoItemBridge(Logger logger) { this.logger = logger; }
    public Identity identity(ItemStack stack) {
        if (!Bukkit.getPluginManager().isPluginEnabled("MMOItems")) {
            if (Bukkit.getPluginManager().getPlugin("MMOItems") != null)
                throw new IllegalStateException("MMOItems instalado pero desactivado: edición de plantillas bloqueada.");
            return null;
        }
        try {
            resolveApi();
            String type = (String) typeMethod.invoke(null, stack);
            String id = (String) idMethod.invoke(null, stack);
            boolean hasType = type != null && !type.isBlank(), hasId = id != null && !id.isBlank();
            if (hasType != hasId) throw new IllegalStateException("Ítem MMOItems incompleto: falta categoría o ID");
            return hasType ? new Identity(type, id) : null;
        } catch (ReflectiveOperationException | LinkageError e) {
            warn(e);
            throw new IllegalStateException("No se pudo identificar MMOItems: no se guardará como snapshot por error.", e);
        }
    }
    public ItemStack create(String type, String id) {
        if (!Bukkit.getPluginManager().isPluginEnabled("MMOItems")) return null;
        try {
            resolveApi();
            Object instance = instanceField.get(null);
            return (ItemStack) createMethod.invoke(instance, type, id);
        } catch (ReflectiveOperationException | LinkageError e) { warn(e); return null; }
    }
    private void warn(Throwable error) {
        if (!reported) {
            reported = true;
            logger.log(Level.WARNING, "API de MMOItems incompatible: deshabilitando la resolución de ítems MMOItems. " +
                    "Comprueba la versión instalada; otros ítems siguen disponibles.", error);
        }
    }
}
