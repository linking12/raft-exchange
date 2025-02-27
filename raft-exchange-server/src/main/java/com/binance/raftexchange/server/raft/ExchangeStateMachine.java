package com.binance.raftexchange.server.raft;

import java.io.DataInput;
import java.io.DataOutput;

import org.jgroups.raft.StateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.binance.raftexchange.stubs.api.ApiAddUser;
import com.binance.raftexchange.stubs.api.ApiCommand;
import com.binance.raftexchange.stubs.command.OrderCommand;
import com.google.protobuf.GeneratedMessageV3;

public class ExchangeStateMachine implements StateMachine {

	private static final Logger LOG = LoggerFactory.getLogger(ExchangeStateMachine.class);

	@Override
	public byte[] apply(byte[] data, int offset, int length, boolean serialize_response) throws Exception {
		GeneratedMessageV3 grpcMessage = SerializeHelper.deserializeWithType(data, offset, length);
		if (grpcMessage instanceof ApiCommand) {
			ApiCommand.CommandCase commandCase = ((ApiCommand) grpcMessage).getCommandCase();
			switch (commandCase) {
			case ADD_USER:

				ApiAddUser apiAddUser = ((ApiCommand) grpcMessage).getAddUser();
				// todo call exchange api
				LOG.info("ApiAddUser applied, msg: {}", apiAddUser);
				break;
			default:
				LOG.warn("Unsupported ApiCommand: {}", commandCase);
			}

		} else if (grpcMessage instanceof OrderCommand) {
			((OrderCommand) grpcMessage).getCommand();
		}
		return null;
	}

	@Override
	public void readContentFrom(DataInput in) throws Exception {

	}

	@Override
	public void writeContentTo(DataOutput out) throws Exception {

	}

}
