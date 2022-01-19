# TemporaryBlocks

Bukkit plugin to remove player placed blocks after a certain, per-type configurable delay.

## Command

> `/temporaryblocks list|reload`  
> View and reload the config  
> **Aliases**: `/tempblocks`, `/tb`  
> **Permission**: `temporaryblocks.command`

## Config

```yaml
# How often in ticks to check for blocks that should be removed
timer-interval: 20

# A material -> seconds map for the resetting times
reset-times:
  obsidian: 60
  end_crystal: 60
```

Material names support `*` wildcards at the start and end.

## Download

https://ci.minebench.de/job/TemporaryBlocks/

## License

```
 TemporaryBlocks
 Copyright (C) 2022 Max Lee aka Phoenix616 (max@themoep.de)

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <https://www.gnu.org/licenses/>.
```