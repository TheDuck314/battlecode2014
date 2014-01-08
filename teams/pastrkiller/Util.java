package pastrkiller;

import battlecode.common.*;

public class Util {
	public static boolean passable(TerrainTile t) {
		return t == TerrainTile.NORMAL || t == TerrainTile.ROAD;
	}
	
	public static MapLocation closest(MapLocation to, MapLocation[] locs) {
		MapLocation ret = null;
		int bestDistSq = 999999;
		for(int i = locs.length; i --> 0; ) {
			MapLocation loc = locs[i];
			int distSq = to.distanceSquaredTo(loc);
			if(distSq < bestDistSq) {
				bestDistSq = distSq;
				ret = loc;
			}
		}
		return ret;
	}
}
