package framework;

import battlecode.common.*;

public class Util {
	public static boolean passable(TerrainTile t) {
		return t == TerrainTile.NORMAL || t == TerrainTile.ROAD;
	}

	public static MapLocation closest(MapLocation[] locs, MapLocation to) {
		MapLocation ret = null;
		int bestDistSq = 999999;
		for (int i = locs.length; i-- > 0;) {
			int distSq = locs[i].distanceSquaredTo(to);
			if (distSq < bestDistSq) {
				distSq = bestDistSq;
				ret = locs[i];
			}
		}
		return ret;
	}

	public static Direction opposite(Direction dir) {
		return Direction.values()[(dir.ordinal() + 4) % 8];
	}
	}
