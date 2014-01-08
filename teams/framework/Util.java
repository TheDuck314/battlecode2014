package framework;

import battlecode.common.*;

public class Util {
	public static boolean passable(TerrainTile t) {
		return t == TerrainTile.NORMAL || t == TerrainTile.ROAD;
	}	
}
