package dev.fce;

import java.util.List;

/**
 * Definicion inmutable de un encantamiento, cargada desde enchants/&lt;id&gt;.yml.
 *
 * Un encantamiento puede tener:
 *   · effects  -> efecto ejecutado por el motor NATIVO de este plugin
 *                 (no depende de Fabled: funciona siempre).
 *   · mechanic -> mecanica de herramienta (vetas, talado, fundicion...),
 *                 imposible de expresar con componentes de skills.
 *   · fabledSkill -> skill opcional de Fabled, por compatibilidad.
 */
public record EnchantDefinition(
        String id,
        String displayName,
        String tierId,
        int maxLevel,
        List<String> applicableGroups,
        String loreLine,
        List<String> description,
        String fabledSkill,
        String icon,
        Effects effects,
        Mechanic mechanic) {

    public boolean hasEffects() {
        return effects != null && effects.actions() != null && !effects.actions().isEmpty();
    }

    public boolean hasMechanic() {
        return mechanic != null && mechanic.type() != null && !mechanic.type().isBlank();
    }

    /** Escalado nativo: valor = base + scale x (nivel - 1). */
    public static double scaled(double base, double scale, int level) {
        return base + scale * Math.max(0, level - 1);
    }

    /** Bloque effects: de enchants/&lt;id&gt;.yml. */
    public record Effects(String trigger, double chanceBase, double chanceScale, List<Action> actions) {
        public double chanceAt(int level) {
            return scaled(chanceBase, chanceScale, level);
        }
    }

    /** Una accion concreta del efecto (potion, heal, damage, fire...). */
    public record Action(String type, String target, String value, int amplifier,
                         double base, double scale,
                         double secondsBase, double secondsScale,
                         boolean trueDamage, double volume, double pitch, int count) {
        public double amountAt(int level) {
            return scaled(base, scale, level);
        }

        public double secondsAt(int level) {
            return scaled(secondsBase, secondsScale, level);
        }

        public boolean onVictim() {
            return "victim".equalsIgnoreCase(target);
        }
    }

    /** Bloque mechanic: de enchants/&lt;id&gt;.yml. */
    public record Mechanic(String type, double base, double scale,
                           double chanceBase, double chanceScale) {
        public double valueAt(int level) {
            return scaled(base, scale, level);
        }

        public double chanceAt(int level) {
            return scaled(chanceBase, chanceScale, level);
        }
    }
}
