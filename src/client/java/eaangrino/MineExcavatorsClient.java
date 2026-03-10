package eaangrino;

import eaangrino.client.ExcavatorAreaOutlineRenderer;
import net.fabricmc.api.ClientModInitializer;

public class MineExcavatorsClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ExcavatorAreaOutlineRenderer.register();
	}
}
