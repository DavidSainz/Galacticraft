package micdoodle8.mods.galacticraft.core.energy.grid;

import buildcraft.api.mj.MjAPI;
import buildcraft.api.power.IPowerReceptor;
import buildcraft.api.power.PowerHandler.PowerReceiver;
//import buildcraft.api.power.PowerHandler.Type;
//import cofh.api.energy.IEnergyHandler;
import cpw.mods.fml.common.FMLLog;
import ic2.api.energy.tile.IEnergyAcceptor;
import ic2.api.energy.tile.IEnergySink;
import mekanism.api.energy.IStrictEnergyAcceptor;
import micdoodle8.mods.galacticraft.api.transmission.NetworkType;
import micdoodle8.mods.galacticraft.api.transmission.grid.IElectricityNetwork;
import micdoodle8.mods.galacticraft.api.transmission.grid.Pathfinder;
import micdoodle8.mods.galacticraft.api.transmission.grid.PathfinderChecker;
import micdoodle8.mods.galacticraft.api.transmission.tile.IConductor;
import micdoodle8.mods.galacticraft.api.transmission.tile.IElectrical;
import micdoodle8.mods.galacticraft.api.transmission.tile.INetworkConnection;
import micdoodle8.mods.galacticraft.api.transmission.tile.INetworkProvider;
import micdoodle8.mods.galacticraft.api.vector.BlockVec3;
import micdoodle8.mods.galacticraft.core.energy.EnergyConfigHandler;
import micdoodle8.mods.galacticraft.core.energy.tile.TileBaseUniversalConductor;
import micdoodle8.mods.galacticraft.core.util.ConfigManagerCore;
import micdoodle8.mods.galacticraft.core.util.GCLog;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import java.lang.reflect.Method;
import java.util.*;


/**
 * A universal network that works with multiple energy systems.
 * 
 * @author radfast, micdoodle8, Calclavia, Aidancbrady
 * 
 */
public class EnergyNetwork implements IElectricityNetwork
{
	/* Re-written by radfast for better performance
	 * 
	 * Imagine a 30 producer, 80 acceptor network...
	 * 
	 *   Before: it would have called the inner loop in produce() 2400 times each tick.  Not good!
	 * 
	 *   After: the inner loop runs 80 times - part of it is in doTickStartCalc() at/near the tick start, and part of it is in doProduce() at the end of the tick
	 */
	public static int tickCount = 0;
	private int tickDone = -1;
	private float totalRequested = 0F;
	private float totalStorageExcess = 0F;
	private float totalEnergy = 0F;
	private float totalSent = 0F;
	private boolean doneScheduled = false;
	private boolean spamstop = false;
	private boolean loopPrevention = false;
	public int networkTierGC = 1;
	private int producersTierGC = 1;

	/*
	 * connectedAcceptors is all the acceptors connected to this network
	 * connectedDirections is the directions of those connections (from the point of view of the acceptor tile)
	 *   Note: each position in those two linked lists matches
	 *         so, an acceptor connected on two sides will be in connectedAcceptors twice 
	 */
	private List<TileEntity> connectedAcceptors = new LinkedList<TileEntity>();
	private List<ForgeDirection> connectedDirections = new LinkedList<ForgeDirection>();

	/*
	 *  availableAcceptors is the acceptors which can receive energy (this tick)
	 *  availableconnectedDirections is a map of those acceptors and the directions they will receive from (from the point of view of the acceptor tile)
	 *    Note: each acceptor will only be included once in these collections
	 *          (there is no point trying to put power into a machine twice from two different sides)
	 */
	private Set<TileEntity> availableAcceptors = new HashSet<TileEntity>();
	private Map<TileEntity, ForgeDirection> availableconnectedDirections = new HashMap<TileEntity, ForgeDirection>();

	private Map<TileEntity, Float> energyRequests = new HashMap<TileEntity, Float>();
	private List<TileEntity> ignoreAcceptors = new LinkedList<TileEntity>();

    private final Set<IConductor> conductors = new HashSet<IConductor>();

	//This is an energy per tick which exceeds what any normal machine will request, so the requester must be an energy storage - for example, a battery or an energy cube
	private final static float ENERGY_STORAGE_LEVEL = 200F;
	
	private Method demandedEnergyIC2 = null;
	private Method injectEnergyIC2 = null;
	private boolean initialisedIC2Methods = false;
	private boolean voltageParameterIC2 = false;

    @Override
    public Set<IConductor> getTransmitters()
    {
        return this.conductors;
    }

    /**
     * Get the total energy request in this network
     *
     * @param ignoreTiles Tiles to ignore in the request calculations (NOTE: only used in initial (internal) check.
     * @return Amount of energy requested in this network
     */
	@Override
	public float getRequest(TileEntity... ignoreTiles)
	{
		if (EnergyNetwork.tickCount != this.tickDone)
		{
			this.tickDone = EnergyNetwork.tickCount;
			//Start the new tick - initialise everything
			this.ignoreAcceptors.clear();
			this.ignoreAcceptors.addAll(Arrays.asList(ignoreTiles));
			this.doTickStartCalc();

			if (EnergyConfigHandler.isBuildcraftLoaded())
			{
				for (IConductor wire : this.getTransmitters())
				{
					if (wire instanceof TileBaseUniversalConductor)
					{
						//This will call getRequest() but that's no problem, on the second call it will just return the totalRequested
						((TileBaseUniversalConductor)wire).reconfigureBC();
					}
				}
			}
		}
		return this.totalRequested - this.totalEnergy - this.totalSent;
	}

    /**
     * Produce energy into the network
     *
     * @param energy Amount of energy to send into the network
     * @param doReceive Whether to put energy into the network (true) or just simulate (false)
     * @param ignoreTiles TileEntities to ignore for energy transfers.
     * @return Amount of energy REMAINING from the passed energy parameter
     */
	@Override
	public float produce(float energy, boolean doReceive, int producerTier, TileEntity... ignoreTiles)
	{
		if (this.loopPrevention) return energy;

		if (energy > 0F)
		{
			if (EnergyNetwork.tickCount != this.tickDone)
			{
				this.tickDone = EnergyNetwork.tickCount;
				//Start the new tick - initialise everything
				this.ignoreAcceptors.clear();
				this.ignoreAcceptors.addAll(Arrays.asList(ignoreTiles));
				this.producersTierGC = 1;
				this.doTickStartCalc();
			}
			else
				this.ignoreAcceptors.addAll(Arrays.asList(ignoreTiles));
	
			if (!this.doneScheduled && this.totalRequested > 0.0F)
			{
				try
				{
					Class<?> clazz = Class.forName("micdoodle8.mods.galacticraft.core.tick.TickHandlerServer");
					clazz.getMethod("scheduleNetworkTick", this.getClass()).invoke(null, this);
				}
				catch (Exception e)
				{
					e.printStackTrace();
				}

				this.doneScheduled = true;
			}

			//On a regular mid-tick produce(), just figure out how much is totalEnergy this tick and return the used amount
			//This will return 0 if totalRequested is 0 - for example a network with no acceptors
			float totalEnergyLast = this.totalEnergy;

			//Add the energy for distribution by this grid later this tick
			//Note: totalEnergy cannot exceed totalRequested
			if (doReceive)
			{
				this.totalEnergy += Math.min(energy, this.totalRequested - totalEnergyLast);
				if (producerTier > 1) this.producersTierGC = 2; 
			}

			if (this.totalRequested >= totalEnergyLast + energy)
			{
				return 0F; //All the electricity will be used
			}
			if (totalEnergyLast >= this.totalRequested)
			{
				return energy; //None of the electricity will be used
			}
			return totalEnergyLast + energy - this.totalRequested; //Some of the electricity will be used
		}
		return energy;
	}

    /**
     * Called on server tick end, from the Galacticraft Core tick handler.
     */
	public void tickEnd()
	{
		this.doneScheduled = false;
		this.loopPrevention = true;
		
		//Finish the last tick if there was some to send and something to receive it
		if (this.totalEnergy > 0F)
		{
			//Call doTickStartCalc a second time in case anything has updated meanwhile
			this.doTickStartCalc();
			
			if (this.totalRequested > 0F)
			{
				this.totalSent = this.doProduce();
				if (this.totalSent < this.totalEnergy)
				{
					//Any spare energy left is retained for the next tick
					this.totalEnergy -= this.totalSent;
				}
				else
				{
					this.totalEnergy = 0F;
				}
			}
		}
		else
		{
			this.totalEnergy = 0F;
		}
		
		this.loopPrevention = false;
	}

    /**
     * Refreshes all tiles in network, and updates requested energy
     *
     * @param ignoreTiles TileEntities to ignore for energy calculations.
     */
	private void doTickStartCalc()
	{
		this.totalSent = 0F;
		this.refreshAcceptors();
		
		if (!this.initialisedIC2Methods)
			this.initialiseIC2Methods();

		if (this.getTransmitters().size() == 0)
		{
			return;
		}

		this.loopPrevention = true;
		
		this.availableAcceptors.clear();
		this.availableconnectedDirections.clear();
		this.energyRequests.clear();
		this.totalRequested = 0.0F;
		this.totalStorageExcess = 0F;

		//boolean isTELoaded = EnergyConfigHandler.isThermalExpansionLoaded();
		boolean isIC2Loaded = EnergyConfigHandler.isIndustrialCraft2Loaded();
		boolean isBCLoaded = EnergyConfigHandler.isBuildcraftLoaded();
		boolean isMekLoaded = EnergyConfigHandler.isMekanismLoaded();

		if (!this.connectedAcceptors.isEmpty())
		{
			float e;
			final Iterator<ForgeDirection> acceptorDirection = this.connectedDirections.iterator();
			for (TileEntity acceptor : this.connectedAcceptors)
			{
				//This tries all sides of the acceptor which are connected (see refreshAcceptors())
				ForgeDirection sideFrom = acceptorDirection.next();

				//But the grid will only put energy into the acceptor from one side - once it's in availableAcceptors
				if (!this.ignoreAcceptors.contains(acceptor) && !this.availableAcceptors.contains(acceptor))
				{
					e = 0.0F;

					if (acceptor instanceof IElectrical)
					{
						e = ((IElectrical) acceptor).getRequest(sideFrom);
					}
					else if (isMekLoaded && acceptor instanceof IStrictEnergyAcceptor)
					{
						e = (float) ((((IStrictEnergyAcceptor) acceptor).getMaxEnergy() - ((IStrictEnergyAcceptor) acceptor).getEnergy()) * EnergyConfigHandler.MEKANISM_RATIO);
					}
					/*else if (isTELoaded && acceptor instanceof IEnergyHandler)
					{
						e = ((IEnergyHandler) acceptor).receiveEnergy(sideFrom, Integer.MAX_VALUE, true) * EnergyConfigHandler.TE_RATIO;
					}*/
					else if (isIC2Loaded && acceptor instanceof IEnergySink)
					{
						double result = 0;
						try {
							result = (Double) this.demandedEnergyIC2.invoke(acceptor);
						} catch (Exception ex) {
							if (ConfigManagerCore.enableDebug) ex.printStackTrace();
						}
						e = (float) result * EnergyConfigHandler.IC2_RATIO;
					}
					else if (isBCLoaded && EnergyConfigHandler.getBuildcraftVersion() == 6 && MjAPI.getMjBattery(acceptor, MjAPI.DEFAULT_POWER_FRAMEWORK, sideFrom) != null)
					//New BC API
					{
						e = (float) MjAPI.getMjBattery(acceptor, MjAPI.DEFAULT_POWER_FRAMEWORK, sideFrom).getEnergyRequested() * EnergyConfigHandler.BC3_RATIO;
					}
					else if (isBCLoaded && acceptor instanceof IPowerReceptor)
					//Legacy BC API
					{
						PowerReceiver BCreceiver = ((IPowerReceptor) acceptor).getPowerReceiver(sideFrom);
						if (BCreceiver != null)
							e = (float) BCreceiver.powerRequest() * EnergyConfigHandler.BC3_RATIO;
					}

					if (e > 0.0F)
					{
						this.availableAcceptors.add(acceptor);
						this.availableconnectedDirections.put(acceptor, sideFrom);
						this.energyRequests.put(acceptor, Float.valueOf(e));
						this.totalRequested += e;
						if (e > EnergyNetwork.ENERGY_STORAGE_LEVEL)
						{
							this.totalStorageExcess += e - EnergyNetwork.ENERGY_STORAGE_LEVEL;
						}
					}
				}
			}
		}

		this.loopPrevention = false;
	}

	/**
     * Complete the energy transfer. Called internally on server tick end.
     *
     * @return Amount of energy SENT to all acceptors
     */
	private float doProduce()
	{
		float sent = 0.0F;

		if (!this.availableAcceptors.isEmpty())
		{
			//boolean isTELoaded = EnergyConfigHandler.isThermalExpansionLoaded();
			boolean isIC2Loaded = EnergyConfigHandler.isIndustrialCraft2Loaded();
			boolean isBCLoaded = EnergyConfigHandler.isBuildcraftLoaded();
			boolean isMekLoaded = EnergyConfigHandler.isMekanismLoaded();

			float energyNeeded = this.totalRequested;
			float energyAvailable = this.totalEnergy;
			float reducor = 1.0F;
			float energyStorageReducor = 1.0F;

			if (energyNeeded > energyAvailable)
			{
				//If not enough energy, try reducing what goes into energy storage (if any)
				energyNeeded -= this.totalStorageExcess;
				//If there's still not enough, put the minimum into energy storage (if any) and, anyhow, reduce everything proportionately
				if (energyNeeded > energyAvailable)
				{
					energyStorageReducor = 0F;
					reducor = energyAvailable / energyNeeded;
				}
				else
				{
					//Energyavailable exceeds the total needed but only if storage does not fill all in one go - this is a common situation
					energyStorageReducor = (energyAvailable - energyNeeded) / this.totalStorageExcess;
				}
			}

			float currentSending;
			float sentToAcceptor;
			int tierProduced = Math.min(this.producersTierGC, this.networkTierGC);

			for (TileEntity tileEntity : this.availableAcceptors)
			{
				//Exit the loop if there is no energy left at all (should normally not happen, should be some even for the last acceptor)
				if (sent >= energyAvailable)
				{
					break;
				}

				//The base case is to give each acceptor what it is requesting
				currentSending = this.energyRequests.get(tileEntity);

				//If it's an energy store, we may need to damp it down if energyStorageReducor is less than 1
				if (currentSending > EnergyNetwork.ENERGY_STORAGE_LEVEL)
				{
					currentSending = EnergyNetwork.ENERGY_STORAGE_LEVEL + (currentSending - EnergyNetwork.ENERGY_STORAGE_LEVEL) * energyStorageReducor;
				}

				//Reduce everything proportionately if there is not enough energy for all needs
				currentSending *= reducor;

				if (currentSending > energyAvailable - sent)
				{
					currentSending = energyAvailable - sent;
				}

				ForgeDirection sideFrom = this.availableconnectedDirections.get(tileEntity);

				if (tileEntity instanceof IElectrical)
				{
					sentToAcceptor = ((IElectrical) tileEntity).receiveElectricity(sideFrom, currentSending, tierProduced, true);
				}
				else if (isMekLoaded && tileEntity instanceof IStrictEnergyAcceptor)
				{
					sentToAcceptor = (float) ((IStrictEnergyAcceptor) tileEntity).transferEnergyToAcceptor(sideFrom, currentSending * EnergyConfigHandler.TO_MEKANISM_RATIO) * EnergyConfigHandler.MEKANISM_RATIO;
				}
//				else if (isTELoaded && tileEntity instanceof IEnergyHandler)
//				{
//				IEnergyHandler handler = (IEnergyHandler) tileEntity;
//				int currentSendinginRF = (currentSending >= Integer.MAX_VALUE / EnergyConfigHandler.TO_TE_RATIO) ? Integer.MAX_VALUE : (int) (currentSending * EnergyConfigHandler.TO_TE_RATIO);
//				sentToAcceptor = handler.receiveEnergy(sideFrom, currentSendinginRF, false) * EnergyConfigHandler.TE_RATIO;
//				}
				else if (isIC2Loaded && tileEntity instanceof IEnergySink)
				{
					double energySendingIC2 = currentSending * EnergyConfigHandler.TO_IC2_RATIO;
					if (energySendingIC2 >= 1D)
					{
						double result = 0;
						try {
							if (this.voltageParameterIC2)
								result = (Double) this.injectEnergyIC2.invoke(tileEntity, sideFrom, energySendingIC2, 120D);
							else
								result = (Double) this.injectEnergyIC2.invoke(tileEntity, sideFrom, energySendingIC2);
						} catch (Exception ex) {
							if (ConfigManagerCore.enableDebug) ex.printStackTrace();
						}
						sentToAcceptor = currentSending - (float) result * EnergyConfigHandler.IC2_RATIO;
						if (sentToAcceptor < 0F) sentToAcceptor = 0F;
					} else
						sentToAcceptor = 0F;
				}
				else if (isBCLoaded && EnergyConfigHandler.getBuildcraftVersion() == 6 && MjAPI.getMjBattery(tileEntity, MjAPI.DEFAULT_POWER_FRAMEWORK, sideFrom) != null)
				//New BC API
				{
					sentToAcceptor = (float) MjAPI.getMjBattery(tileEntity, MjAPI.DEFAULT_POWER_FRAMEWORK, sideFrom).addEnergy(currentSending * EnergyConfigHandler.TO_BC_RATIO) * EnergyConfigHandler.BC3_RATIO;
				}
				else if (isBCLoaded && tileEntity instanceof IPowerReceptor)
				//Legacy BC API
				{
					PowerReceiver receiver = ((IPowerReceptor) tileEntity).getPowerReceiver(sideFrom);

					if (receiver != null)
					{
						double toSendBC = Math.min(currentSending * EnergyConfigHandler.TO_BC_RATIO, receiver.powerRequest());
						sentToAcceptor = (float) receiver.receiveEnergy(buildcraft.api.power.PowerHandler.Type.PIPE, toSendBC, sideFrom) * EnergyConfigHandler.BC3_RATIO;
					} else
						sentToAcceptor = 0F;
				}
				else
				{
					sentToAcceptor = 0F;
				}

				if (sentToAcceptor / currentSending > 1.002F && sentToAcceptor > 0.01F)
				{
					if (!this.spamstop)
					{
						FMLLog.info("Energy network: acceptor took too much energy, offered " + currentSending + ", took " + sentToAcceptor + ". " + tileEntity.toString());
						this.spamstop = true;
					}
					sentToAcceptor = currentSending;
				}

				sent += sentToAcceptor;
			}
		}

		if (EnergyNetwork.tickCount % 200 == 0)
		{
			this.spamstop = false;
		}

		float returnvalue = sent;
		if (returnvalue > this.totalEnergy)
		{
			returnvalue = this.totalEnergy;
		}
		if (returnvalue < 0F)
		{
			returnvalue = 0F;
		}
		return returnvalue;
	}

    /**
     * Refresh validity of each conductor in the network
     */
	@Override
	public void refresh()
	{
		int tierfound = 2;
		Iterator<IConductor> it = this.getTransmitters().iterator();
		while (it.hasNext())
		{
			IConductor conductor = it.next();

			if (conductor == null)
			{
				it.remove();
				continue;
			}

			TileEntity tile = (TileEntity) conductor;
			World world = tile.getWorldObj();
			//Remove any conductors in unloaded chunks
			if (tile.isInvalid() || world == null || !world.blockExists(tile.xCoord, tile.yCoord, tile.zCoord))
			{
				it.remove();
				continue;
			}

			if (conductor != world.getTileEntity(tile.xCoord, tile.yCoord, tile.zCoord))
			{
				it.remove();
				continue;
			}

			if (conductor.getTierGC() < 2)
				tierfound = 1;
			
			if (conductor.getNetwork() != this)
			{
				conductor.setNetwork(this);
				conductor.onNetworkChanged();
			}
		}
		
		//This will set the network tier to 2 if all the conductors are tier 2
		this.networkTierGC = tierfound;
	}

    /**
     * Refresh all energy acceptors in the network
     */
	private void refreshAcceptors()
	{
		this.connectedAcceptors.clear();
		this.connectedDirections.clear();

		this.refresh();

		try
		{
			//boolean isTELoaded = EnergyConfigHandler.isThermalExpansionLoaded();
			boolean isIC2Loaded = EnergyConfigHandler.isIndustrialCraft2Loaded();
			boolean isBCLoaded = EnergyConfigHandler.isBuildcraftLoaded();
			boolean isMekLoaded = EnergyConfigHandler.isMekanismLoaded();

			LinkedList<IConductor> conductors = new LinkedList();
			conductors.addAll(this.getTransmitters());
			//This prevents concurrent modifications if something in the loop causes chunk loading
			//(Chunk loading can change the network if new conductors are found)
			for (IConductor conductor : conductors)
			{
				TileEntity[] adjacentConnections = conductor.getAdjacentConnections();
				for (int i = 0; i < adjacentConnections.length; i++)
				{
					TileEntity acceptor = adjacentConnections[i];

					if (acceptor != null && !(acceptor instanceof IConductor) && !acceptor.isInvalid())
					{
						// The direction 'sideFrom' is from the perspective of the acceptor, that's more useful than the conductor's perspective
						ForgeDirection sideFrom = ForgeDirection.getOrientation(i).getOpposite();

						if (acceptor instanceof IElectrical)
						{
							if (((IElectrical) acceptor).canConnect(sideFrom, NetworkType.POWER))
							{
								this.connectedAcceptors.add(acceptor);
								this.connectedDirections.add(sideFrom);
							}
						}
						else if (isMekLoaded && acceptor instanceof IStrictEnergyAcceptor)
						{
							if (((IStrictEnergyAcceptor) acceptor).canReceiveEnergy(sideFrom))
							{
								this.connectedAcceptors.add(acceptor);
								this.connectedDirections.add(sideFrom);
							}
						}
						/*else if (isTELoaded && acceptor instanceof IEnergyHandler)
						{
							if (((IEnergyHandler) acceptor).canInterface(sideFrom))
							{
								this.connectedAcceptors.add(acceptor);
								this.connectedDirections.add(sideFrom);
							}
						}*/
						else if (isIC2Loaded && acceptor instanceof IEnergyAcceptor)
						{
							if(((IEnergyAcceptor)acceptor).acceptsEnergyFrom((TileEntity) conductor, sideFrom))
							{
								this.connectedAcceptors.add(acceptor);
								this.connectedDirections.add(sideFrom);
							}
						}
						else if (isBCLoaded && EnergyConfigHandler.getBuildcraftVersion() == 6 && MjAPI.getMjBattery(acceptor, MjAPI.DEFAULT_POWER_FRAMEWORK, sideFrom) != null)
						{
							this.connectedAcceptors.add(acceptor);
							this.connectedDirections.add(sideFrom);						
						}
						else if (isBCLoaded && acceptor instanceof IPowerReceptor)
						{
							if (((IPowerReceptor) acceptor).getPowerReceiver(sideFrom) != null)
							{
								this.connectedAcceptors.add(acceptor);
								this.connectedDirections.add(sideFrom);
							}
						}
					}
				}
			}
		}
		catch (Exception e)
		{
			FMLLog.severe("Energy Network: Error when trying to refresh list of power acceptors.");
			e.printStackTrace();
		}
	}

    /**
     * Combine this network with another electricitynetwork
     *
     * @param network Network to merge with
     * @return The final, joined network
     */
	@Override
	public IElectricityNetwork merge(IElectricityNetwork network)
	{
		if (network != null && network != this)
		{
			Set<IConductor> thisNetwork = this.getTransmitters();
			Set<IConductor> thatNetwork = network.getTransmitters();
			if (thisNetwork.size() >= thatNetwork.size())
			{
				thisNetwork.addAll(thatNetwork);
				this.refresh();
				if (network instanceof EnergyNetwork)
				{
					((EnergyNetwork) network).destroy();
				}
				return this;
			}
			else
			{
				thatNetwork.addAll(thisNetwork);
				network.refresh();
				this.destroy();
				return network;
			}
		}

		return this;
	}

	private void destroy()
	{
		this.getTransmitters().clear();
		this.connectedAcceptors.clear();
		this.availableAcceptors.clear();
		this.totalEnergy = 0F;
		this.totalRequested = 0F;
		try
		{
			Class<?> clazz = Class.forName("micdoodle8.mods.galacticraft.core.tick.TickHandlerServer");
			clazz.getMethod("removeNetworkTick", this.getClass()).invoke(null, this);
		}
		catch (Exception e)
		{
			e.printStackTrace();
		}

	}

    /**
     * Split the network into two separate networks at a specific tile.
     *
     * A new network will be created for one side of the broken network, and the current one will update for the rest.
     *
     * If the block is broken, but the network is still connected without that tile, no new network will be created.
     *
     * @param splitPoint
     */
	@Override
	public void split(IConductor splitPoint)
	{
		if (splitPoint instanceof TileEntity)
		{
			this.getTransmitters().remove(splitPoint);

			/**
			 * Loop through the connected blocks and attempt to see if there are
			 * connections between the two points elsewhere.
			 */
			TileEntity[] connectedBlocks = splitPoint.getAdjacentConnections();

			for (TileEntity connectedBlockA : connectedBlocks)
			{
				if (connectedBlockA instanceof INetworkConnection)
				{
					for (final TileEntity connectedBlockB : connectedBlocks)
					{
						if (connectedBlockA != connectedBlockB && connectedBlockB instanceof INetworkConnection)
						{
							Pathfinder finder = new PathfinderChecker(((TileEntity) splitPoint).getWorldObj(), (INetworkConnection) connectedBlockB, NetworkType.POWER, splitPoint);
							finder.init(new BlockVec3(connectedBlockA));

							if (finder.results.size() > 0)
							{
								/**
								 * The connections A and B are still intact
								 * elsewhere. Set all references of wire
								 * connection into one network.
								 */

								for (BlockVec3 node : finder.closedSet)
								{
									TileEntity nodeTile = node.getTileEntity(((TileEntity) splitPoint).getWorldObj());

									if (nodeTile instanceof INetworkProvider)
									{
										if (nodeTile != splitPoint)
										{
											((INetworkProvider) nodeTile).setNetwork(this);
										}
									}
								}
							}
							else
							{
								/**
								 * The connections A and B are not connected
								 * anymore. Give both of them a new network.
								 */
								IElectricityNetwork newNetwork = new EnergyNetwork();

								for (BlockVec3 node : finder.closedSet)
								{
									TileEntity nodeTile = node.getTileEntity(((TileEntity) splitPoint).getWorldObj());

									if (nodeTile instanceof INetworkProvider)
									{
										if (nodeTile != splitPoint)
										{
											newNetwork.getTransmitters().add((IConductor) nodeTile);
										}
									}
								}

								newNetwork.refresh();
							}
						}
					}
				}
			}
		}
	}

	@Override
	public String toString()
	{
		return "EnergyNetwork[" + this.hashCode() + "|Wires:" + this.getTransmitters().size() + "|Acceptors:" + this.connectedAcceptors.size() + "]";
	}
	
    private void initialiseIC2Methods()
    {
		if (EnergyConfigHandler.isIndustrialCraft2Loaded())
		{
			if (ConfigManagerCore.enableDebug) GCLog.info("Debug UN: Initialising IC2 methods");
			try
			{
				Class<?> clazz = Class.forName("ic2.api.energy.tile.IEnergySink");

				if (ConfigManagerCore.enableDebug) GCLog.info("Debug UN: Found IC2 IEnergySink class");
				try {
					//1.7.2 version
					this.demandedEnergyIC2 = clazz.getMethod("demandedEnergyUnits");
				} catch (Exception e)
				{
					//if that fails, try 1.7.10 version
					try {
						this.demandedEnergyIC2 = clazz.getMethod("getDemandedEnergy");
					} catch (Exception ee) { ee.printStackTrace(); }				
				}

				if (ConfigManagerCore.enableDebug) GCLog.info("Debug UN: Set IC2 demandedEnergy method");

				try {
					//1.7.2 version
					this.injectEnergyIC2 = clazz.getMethod("injectEnergyUnits", ForgeDirection.class, double.class);
					if (ConfigManagerCore.enableDebug) GCLog.info("Debug UN: IC2 inject 1.7.2 succeeded");
				} catch (Exception e)
				{
					//if that fails, try 1.7.10 version
					try {
						this.injectEnergyIC2 = clazz.getMethod("injectEnergy", ForgeDirection.class, double.class, double.class);
						this.voltageParameterIC2 = true;
						if (ConfigManagerCore.enableDebug) GCLog.info("Debug UN: IC2 inject 1.7.10 succeeded");
					} catch (Exception ee) { ee.printStackTrace(); }				
				}
			}
			catch (Exception e)
			{
				e.printStackTrace();
			}
		}
    	this.initialisedIC2Methods = true;
	}
}
