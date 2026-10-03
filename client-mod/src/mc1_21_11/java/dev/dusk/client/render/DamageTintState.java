package dev.dusk.client.render;

/**
 * What DamageTint needs at submit time, carried on the living entity's render
 * state itself (see DamageStateMixin) so nothing is looked up per entity.
 */
public interface DamageTintState {
    void duskclient$setDamage(int hurtTime, int deathTime, int variant);

    int duskclient$hurtTime();

    int duskclient$deathTime();

    int duskclient$variant();
}
