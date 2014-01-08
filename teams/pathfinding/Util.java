package pathfinding;

import battlecode.common.MapLocation;
import battlecode.common.TerrainTile;

public class Util {
	public static boolean passable(TerrainTile t) {
		return t == TerrainTile.NORMAL || t == TerrainTile.ROAD;
	}	
}
