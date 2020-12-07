// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.protocol.config

import org.alephium.protocol.mining.Emission
import org.alephium.protocol.model.Target
import org.alephium.util.Duration

trait ConsensusConfigFixture {
  implicit val consensusConfig: ConsensusConfig = new ConsensusConfig {
    override val blockTargetTime: Duration = Duration.ofSecondsUnsafe(64)

    override val numZerosAtLeastInHash: Int = 0
    override val maxMiningTarget: Target    = Target.Max
    override val tipsPruneInterval: Int     = 2

    override val emission: Emission =
      Emission(new GroupConfig {
        override def groups: Int = 3
      }, blockTargetTime)
  }
}
