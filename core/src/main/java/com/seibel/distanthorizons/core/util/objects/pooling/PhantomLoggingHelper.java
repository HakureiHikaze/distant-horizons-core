package com.seibel.distanthorizons.core.util.objects.pooling;

import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.util.objects.Pair;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class PhantomLoggingHelper
{
	/**
	 * This was separated out so it could be used for other string pair lists.
	 * James originally had an idea to add a shorter static string
	 * ID to each allocated {@link PhantomArrayListCheckout} as a simpler version of the stack trace,
	 * however it became a bit more difficult and messy than he wanted to deal with, so for now we just
	 * have the stack trace.
	 */
	public static void putAndIncrementTrackingString(
		String key,
		ArrayList<Pair<String, AtomicInteger>> allocationStackTraceCountPairList)
	{
		// sequential search, for the number of elements we're dealing with (less than 20)
		// this should be sufficiently fast
		boolean pairFound = false;
		for (int i = 0; i < allocationStackTraceCountPairList.size(); i++)
		{
			Pair<String, AtomicInteger> possiblePair = allocationStackTraceCountPairList.get(i);
			if (possiblePair.first.equals(key))
			{
				possiblePair.second.getAndIncrement();
				pairFound = true;
				break;
			}
		}
		
		if (!pairFound)
		{
			allocationStackTraceCountPairList.add(new Pair<>(key, new AtomicInteger(1)));
		}
	}
	
	public static void LogAllocationStackTracePairCounts(DhLogger logger, ArrayList<Pair<String, AtomicInteger>> allocationStackTraceCountPairList)
	{
		// high numbers first
		allocationStackTraceCountPairList.sort((a, b) -> Integer.compare(b.second.get(), a.second.get()));
		
		StringBuilder stringBuilder = new StringBuilder();
		for (int j = 0; j < allocationStackTraceCountPairList.size(); j++)
		{
			int count = allocationStackTraceCountPairList.get(j).second.get();
			String stack = allocationStackTraceCountPairList.get(j).first;
			
			stringBuilder.append(count).append(". ").append(stack).append("\n");
		}
		logger.warn("Stacks: ["+ allocationStackTraceCountPairList.size()+"]\n" + stringBuilder.toString());
	}
	
	
	
}
