use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
use exchange_core_rs::core::common::cmd::order_command::OrderCommand;
use exchange_core_rs::core::common::cmd::order_command_type::OrderCommandType;
use exchange_core_rs::core::common::l2_market_data::L2MarketData;
use exchange_core_rs::core::common::matcher_event_type::MatcherEventType;
use exchange_core_rs::core::common::matcher_trade_event::MatcherTradeEvent;
use exchange_core_rs::core::common::order_action::OrderAction;
use exchange_core_rs::core::common::order_type::OrderType;
use exchange_core_rs::core::orderbook::i_order_book::IOrderBook;
use exchange_core_rs::core::orderbook::order_book_direct_impl::OrderBookDirectImpl;
use exchange_core_rs::core::orderbook::order_book_naive_impl::OrderBookNaiveImpl;

use OrderAction::{Ask, Bid};
use OrderType::{FokBudget, Gtc, Ioc, IocBudget};

const INITIAL_PRICE: i64 = 81600;
const MAX_PRICE: i64 = 400000;
const UID_1: i64 = 412;
const UID_2: i64 = 413;

#[derive(Clone)]
struct L2Helper {
    ask_prices: Vec<i64>,
    ask_volumes: Vec<i64>,
    ask_orders: Vec<i64>,
    bid_prices: Vec<i64>,
    bid_volumes: Vec<i64>,
    bid_orders: Vec<i64>,
}

impl L2Helper {
    fn build(&self) -> L2MarketData {
        L2MarketData {
            ask_prices: self.ask_prices.clone(),
            ask_volumes: self.ask_volumes.clone(),
            ask_orders: self.ask_orders.clone(),
            bid_prices: self.bid_prices.clone(),
            bid_volumes: self.bid_volumes.clone(),
            bid_orders: self.bid_orders.clone(),
        }
    }

    fn aggregate_buy_budget(&self, mut size: i64) -> i64 {
        let mut budget = 0i64;
        for i in 0..self.ask_prices.len() {
            let v = self.ask_volumes[i];
            let p = self.ask_prices[i];
            if v < size {
                budget += v * p;
                size -= v;
            } else {
                return budget + size * p;
            }
        }
        panic!("Can not collect size {size}");
    }

    fn aggregate_sell_expectation(&self, mut size: i64) -> i64 {
        let mut expectation = 0i64;
        for i in 0..self.bid_prices.len() {
            let v = self.bid_volumes[i];
            let p = self.bid_prices[i];
            if v < size {
                expectation += v * p;
                size -= v;
            } else {
                return expectation + size * p;
            }
        }
        panic!("Can not collect size {size}");
    }

    fn set_ask_volume(&mut self, pos: usize, v: i64) -> &mut Self {
        self.ask_volumes[pos] = v;
        self
    }
    fn set_bid_volume(&mut self, pos: usize, v: i64) -> &mut Self {
        self.bid_volumes[pos] = v;
        self
    }
    fn decrement_bid_volume(&mut self, pos: usize, d: i64) -> &mut Self {
        self.bid_volumes[pos] -= d;
        self
    }
    fn decrement_ask_orders_num(&mut self, pos: usize) -> &mut Self {
        self.ask_orders[pos] -= 1;
        self
    }
    fn decrement_bid_orders_num(&mut self, pos: usize) -> &mut Self {
        self.bid_orders[pos] -= 1;
        self
    }
    fn increment_bid_orders_num(&mut self, pos: usize) -> &mut Self {
        self.bid_orders[pos] += 1;
        self
    }
    fn remove_ask(&mut self, pos: usize) -> &mut Self {
        self.ask_prices.remove(pos);
        self.ask_volumes.remove(pos);
        self.ask_orders.remove(pos);
        self
    }
    fn remove_all_asks(&mut self) -> &mut Self {
        self.ask_prices.clear();
        self.ask_volumes.clear();
        self.ask_orders.clear();
        self
    }
    fn remove_bid(&mut self, pos: usize) -> &mut Self {
        self.bid_prices.remove(pos);
        self.bid_volumes.remove(pos);
        self.bid_orders.remove(pos);
        self
    }
    fn insert_ask(&mut self, pos: usize, price: i64, volume: i64) -> &mut Self {
        self.ask_prices.insert(pos, price);
        self.ask_volumes.insert(pos, volume);
        self.ask_orders.insert(pos, 1);
        self
    }
    fn insert_bid(&mut self, pos: usize, price: i64, volume: i64) -> &mut Self {
        self.bid_prices.insert(pos, price);
        self.bid_volumes.insert(pos, volume);
        self.bid_orders.insert(pos, 1);
        self
    }
}

struct Fixture {
    ob: Box<dyn IOrderBook>,
    exp: L2Helper,
}

impl Fixture {
    fn naive() -> Self {
        Self::build(Box::new(OrderBookNaiveImpl::new()))
    }
    fn direct() -> Self {
        Self::build(Box::new(OrderBookDirectImpl::new()))
    }

    fn build(ob: Box<dyn IOrderBook>) -> Self {
        let mut fx = Fixture {
            ob,
            exp: L2Helper {
                ask_prices: vec![81599, 81600, 200954, 201000],
                ask_volumes: vec![75, 100, 10, 60],
                ask_orders: vec![2, 1, 1, 2],
                bid_prices: vec![81593, 81590, 81200, 10000, 9136],
                bid_volumes: vec![40, 21, 20, 13, 2],
                bid_orders: vec![1, 2, 1, 2, 1],
            },
        };

        fx.newo(Gtc, 0, UID_2, INITIAL_PRICE, 0, 13, Ask, CommandResultCode::Success);
        fx.cancel(0, UID_2, CommandResultCode::Success);

        fx.newo(Gtc, 1, UID_1, 81600, 0, 100, Ask, CommandResultCode::Success);
        fx.newo(Gtc, 2, UID_1, 81599, 0, 50, Ask, CommandResultCode::Success);
        fx.newo(Gtc, 3, UID_1, 81599, 0, 25, Ask, CommandResultCode::Success);
        fx.newo(Gtc, 8, UID_1, 201000, 0, 28, Ask, CommandResultCode::Success);
        fx.newo(Gtc, 9, UID_1, 201000, 0, 32, Ask, CommandResultCode::Success);
        fx.newo(Gtc, 10, UID_1, 200954, 0, 10, Ask, CommandResultCode::Success);

        fx.newo(Gtc, 4, UID_1, 81593, 82000, 40, Bid, CommandResultCode::Success);
        fx.newo(Gtc, 5, UID_1, 81590, 82000, 20, Bid, CommandResultCode::Success);
        fx.newo(Gtc, 6, UID_1, 81590, 82000, 1, Bid, CommandResultCode::Success);
        fx.newo(Gtc, 7, UID_1, 81200, 82000, 20, Bid, CommandResultCode::Success);
        fx.newo(Gtc, 11, UID_1, 10000, 12000, 12, Bid, CommandResultCode::Success);
        fx.newo(Gtc, 12, UID_1, 10000, 12000, 1, Bid, CommandResultCode::Success);
        fx.newo(Gtc, 13, UID_1, 9136, 12000, 2, Bid, CommandResultCode::Success);

        assert_eq!(fx.snapshot(), fx.exp.build(), "initial golden order book mismatch");
        fx
    }

    fn snapshot(&self) -> L2MarketData {
        self.ob.fill_l2(-1)
    }

    fn assert_l2(&self) {
        assert_eq!(self.snapshot(), self.exp.build());
    }

    #[allow(clippy::too_many_arguments)]
    fn newo(
        &mut self,
        ot: OrderType,
        id: i64,
        uid: i64,
        price: i64,
        reserve: i64,
        size: i64,
        action: OrderAction,
        expect: CommandResultCode,
    ) -> OrderCommand {
        let mut cmd = OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: id,
            symbol: 1,
            price,
            size,
            reserve_bid_price: reserve,
            action: Some(action),
            order_type: Some(ot),
            uid,
            ..Default::default()
        };
        let rc = self.ob.new_order(&mut cmd);
        assert_eq!(rc, expect, "place order {id} result code mismatch");
        cmd
    }

    fn cancel(&mut self, id: i64, uid: i64, expect: CommandResultCode) -> OrderCommand {
        let mut cmd = OrderCommand {
            command: OrderCommandType::CancelOrder,
            order_id: id,
            uid,
            ..Default::default()
        };
        let rc = self.ob.cancel_order(&mut cmd);
        assert_eq!(rc, expect, "cancel {id} result code mismatch");
        cmd
    }

    fn reduce(&mut self, id: i64, uid: i64, size: i64, expect: CommandResultCode) -> OrderCommand {
        let mut cmd = OrderCommand {
            command: OrderCommandType::ReduceOrder,
            order_id: id,
            uid,
            size,
            ..Default::default()
        };
        let rc = self.ob.reduce_order(&mut cmd);
        assert_eq!(rc, expect, "reduce {id} result code mismatch");
        cmd
    }

    fn update(&mut self, id: i64, uid: i64, price: i64, expect: CommandResultCode) -> OrderCommand {
        let mut cmd = OrderCommand {
            command: OrderCommandType::MoveOrder,
            order_id: id,
            uid,
            price,
            ..Default::default()
        };
        let rc = self.ob.move_order(&mut cmd);
        assert_eq!(rc, expect, "move {id} result code mismatch");
        cmd
    }

    fn clear(&mut self) {
        let snap = self.snapshot();
        let ask_sum: i64 = snap.ask_volumes.iter().sum();
        if ask_sum > 0 {
            let mut c = OrderCommand {
                command: OrderCommandType::PlaceOrder,
                order_id: 100000000000,
                symbol: 1,
                price: MAX_PRICE,
                size: ask_sum,
                reserve_bid_price: MAX_PRICE,
                action: Some(Bid),
                order_type: Some(Ioc),
                uid: -1,
                ..Default::default()
            };
            self.ob.new_order(&mut c);
        }
        let bid_sum: i64 = snap.bid_volumes.iter().sum();
        if bid_sum > 0 {
            let mut c = OrderCommand {
                command: OrderCommandType::PlaceOrder,
                order_id: 100000000001,
                symbol: 1,
                price: 1,
                size: bid_sum,
                reserve_bid_price: 0,
                action: Some(Ask),
                order_type: Some(Ioc),
                uid: -2,
                ..Default::default()
            };
            self.ob.new_order(&mut c);
        }
        let after = self.snapshot();
        assert!(after.ask_prices.is_empty(), "asks remain after clear");
        assert!(after.bid_prices.is_empty(), "bids remain after clear");
    }
}

fn events(cmd: &OrderCommand) -> Vec<&MatcherTradeEvent> {
    let mut out = Vec::new();
    let mut cur = cmd.matcher_event.as_deref();
    while let Some(e) = cur {
        out.push(e);
        cur = e.next.as_deref();
    }
    out
}

fn check_trade(ev: &MatcherTradeEvent, matched_id: i64, price: i64, size: i64) {
    assert_eq!(ev.event_type, MatcherEventType::Trade);
    assert_eq!(ev.maker_order_id, matched_id, "matched_order_id");
    assert_eq!(ev.price, price, "trade price");
    assert_eq!(ev.size, size, "trade size");
}

fn check_reject(ev: &MatcherTradeEvent, size: i64, price: i64, bidder_hold_price: i64) {
    assert_eq!(ev.event_type, MatcherEventType::Reject);
    assert_eq!(ev.size, size, "reject size");
    assert_eq!(ev.price, price, "reject price");
    assert!(ev.active_order_completed, "reject active_order_completed");
    assert_eq!(ev.bidder_hold_price, bidder_hold_price, "reject bidder_hold_price");
}

fn check_reduce(ev: &MatcherTradeEvent, reduce_size: i64, price: i64, completed: bool) {
    assert_eq!(ev.event_type, MatcherEventType::Reduce);
    assert_eq!(ev.size, reduce_size, "reduce size");
    assert_eq!(ev.price, price, "reduce price");
    assert_eq!(ev.active_order_completed, completed, "reduce active_order_completed");
    assert!(ev.next.is_none(), "reduce event should have no successor");
}

fn scn_should_initialize_without_errors(_fx: &mut Fixture) {
}

fn scn_should_add_gtc_orders(fx: &mut Fixture) {
    fx.newo(Gtc, 93, UID_1, 81598, 0, 1, Ask, CommandResultCode::Success);
    fx.exp.insert_ask(0, 81598, 1);
    fx.newo(Gtc, 94, UID_1, 81594, MAX_PRICE, 9_000_000_000, Bid, CommandResultCode::Success);
    fx.exp.insert_bid(0, 81594, 9_000_000_000);
    fx.assert_l2();

    fx.newo(Gtc, 95, UID_1, 130000, 0, 13_000_000_000, Ask, CommandResultCode::Success);
    fx.exp.insert_ask(3, 130000, 13_000_000_000);
    fx.newo(Gtc, 96, UID_1, 1000, MAX_PRICE, 4, Bid, CommandResultCode::Success);
    fx.exp.insert_bid(6, 1000, 4);
    fx.assert_l2();
}

fn scn_should_ignored_duplicate_order(fx: &mut Fixture) {
    let cmd = fx.newo(Gtc, 1, UID_1, 81600, 0, 100, Ask, CommandResultCode::Success);
    assert_eq!(events(&cmd).len(), 1);
}

fn scn_should_remove_bid_order(fx: &mut Fixture) {
    let cmd = fx.cancel(5, UID_1, CommandResultCode::Success);
    fx.exp.set_bid_volume(1, 1).decrement_bid_orders_num(1);
    fx.assert_l2();
    assert_eq!(cmd.action, Some(Bid));
    let ev = events(&cmd);
    assert_eq!(ev.len(), 1);
    check_reduce(ev[0], 20, 81590, true);
}

fn scn_should_remove_ask_order(fx: &mut Fixture) {
    let cmd = fx.cancel(2, UID_1, CommandResultCode::Success);
    fx.exp.set_ask_volume(0, 25).decrement_ask_orders_num(0);
    fx.assert_l2();
    assert_eq!(cmd.action, Some(Ask));
    let ev = events(&cmd);
    assert_eq!(ev.len(), 1);
    check_reduce(ev[0], 50, 81599, true);
}

fn scn_should_reduce_bid_order(fx: &mut Fixture) {
    let cmd = fx.reduce(5, UID_1, 3, CommandResultCode::Success);
    fx.exp.decrement_bid_volume(1, 3);
    fx.assert_l2();
    assert_eq!(cmd.action, Some(Bid));
    let ev = events(&cmd);
    assert_eq!(ev.len(), 1);
    check_reduce(ev[0], 3, 81590, false);
}

fn scn_should_reduce_ask_order(fx: &mut Fixture) {
    let cmd = fx.reduce(1, UID_1, 300, CommandResultCode::Success);
    fx.exp.remove_ask(1);
    fx.assert_l2();
    assert_eq!(cmd.action, Some(Ask));
    let ev = events(&cmd);
    assert_eq!(ev.len(), 1);
    check_reduce(ev[0], 100, 81600, true);
}

fn scn_should_remove_order_and_empty_bucket(fx: &mut Fixture) {
    let cmd2 = fx.cancel(2, UID_1, CommandResultCode::Success);
    assert_eq!(cmd2.action, Some(Ask));
    let ev = events(&cmd2);
    assert_eq!(ev.len(), 1);
    check_reduce(ev[0], 50, 81599, true);

    let cmd3 = fx.cancel(3, UID_1, CommandResultCode::Success);
    assert_eq!(cmd3.action, Some(Ask));
    fx.exp.remove_ask(0);
    fx.assert_l2();
    let ev = events(&cmd3);
    assert_eq!(ev.len(), 1);
    check_reduce(ev[0], 25, 81599, true);
}

fn scn_should_return_error_when_deleting_unknown_order(fx: &mut Fixture) {
    let cmd = fx.cancel(5291, UID_1, CommandResultCode::MatchingUnknownOrderId);
    fx.assert_l2();
    assert_eq!(events(&cmd).len(), 0);
}

fn scn_should_return_error_when_deleting_other_user_order(fx: &mut Fixture) {
    let cmd = fx.cancel(3, UID_2, CommandResultCode::MatchingUnknownOrderId);
    assert!(cmd.matcher_event.is_none());
    fx.assert_l2();
}

fn scn_should_return_error_when_updating_other_user_order(fx: &mut Fixture) {
    let cmd = fx.update(2, UID_2, 100, CommandResultCode::MatchingUnknownOrderId);
    assert!(cmd.matcher_event.is_none());
    let cmd = fx.update(8, UID_2, 100, CommandResultCode::MatchingUnknownOrderId);
    assert!(cmd.matcher_event.is_none());
    fx.assert_l2();
}

fn scn_should_return_error_when_updating_unknown_order(fx: &mut Fixture) {
    let cmd = fx.update(2433, UID_1, 300, CommandResultCode::MatchingUnknownOrderId);
    fx.assert_l2();
    assert_eq!(events(&cmd).len(), 0);
}

fn scn_should_return_error_when_reducing_unknown_order(fx: &mut Fixture) {
    let cmd = fx.reduce(3, UID_2, 1, CommandResultCode::MatchingUnknownOrderId);
    assert!(cmd.matcher_event.is_none());
    fx.assert_l2();
}

fn scn_should_return_error_when_reducing_by_zero_or_negative_size(fx: &mut Fixture) {
    let cmd = fx.reduce(4, UID_1, 0, CommandResultCode::MatchingReduceFailedWrongSize);
    assert!(cmd.matcher_event.is_none());
    let cmd = fx.reduce(8, UID_1, -1, CommandResultCode::MatchingReduceFailedWrongSize);
    assert!(cmd.matcher_event.is_none());
    let cmd = fx.reduce(8, UID_1, i64::MIN, CommandResultCode::MatchingReduceFailedWrongSize);
    assert!(cmd.matcher_event.is_none());
    fx.assert_l2();
}

fn scn_should_return_error_when_reducing_other_user_order(fx: &mut Fixture) {
    let cmd = fx.reduce(8, UID_2, 3, CommandResultCode::MatchingUnknownOrderId);
    assert!(cmd.matcher_event.is_none());
    fx.assert_l2();
}

fn scn_should_move_order_existing_bucket(fx: &mut Fixture) {
    let cmd = fx.update(7, UID_1, 81590, CommandResultCode::Success);
    fx.exp.set_bid_volume(1, 41).increment_bid_orders_num(1).remove_bid(2);
    fx.assert_l2();
    assert_eq!(events(&cmd).len(), 0);
}

fn scn_should_move_order_new_bucket(fx: &mut Fixture) {
    let cmd = fx.update(7, UID_1, 81594, CommandResultCode::Success);
    fx.exp.remove_bid(2).insert_bid(0, 81594, 20);
    fx.assert_l2();
    assert_eq!(events(&cmd).len(), 0);
}

fn scn_should_match_ioc_order_partial_bbo(fx: &mut Fixture) {
    let cmd = fx.newo(Ioc, 123, UID_2, 1, 0, 10, Ask, CommandResultCode::Success);
    fx.exp.set_bid_volume(0, 30);
    fx.assert_l2();
    let ev = events(&cmd);
    assert_eq!(ev.len(), 1);
    check_trade(ev[0], 4, 81593, 10);
}

fn scn_should_match_ioc_order_full_bbo(fx: &mut Fixture) {
    let cmd = fx.newo(Ioc, 123, UID_2, 1, 0, 40, Ask, CommandResultCode::Success);
    fx.exp.remove_bid(0);
    fx.assert_l2();
    let ev = events(&cmd);
    assert_eq!(ev.len(), 1);
    check_trade(ev[0], 4, 81593, 40);
}

fn scn_should_match_ioc_order_with_two_limit_orders_partial(fx: &mut Fixture) {
    let cmd = fx.newo(Ioc, 123, UID_2, 1, 0, 41, Ask, CommandResultCode::Success);
    fx.exp.remove_bid(0).set_bid_volume(0, 20);
    fx.assert_l2();
    let ev = events(&cmd);
    assert_eq!(ev.len(), 2);
    check_trade(ev[0], 4, 81593, 40);
    check_trade(ev[1], 5, 81590, 1);
    let orders = fx.ob.find_user_orders(UID_1);
    assert!(!orders.iter().any(|o| o.order_id == 4), "order 4 should have been removed");
    assert!(orders.iter().any(|o| o.order_id == 5), "order 5 should still be present");
}

fn scn_should_match_ioc_order_full_liquidity(fx: &mut Fixture) {
    let cmd = fx.newo(Ioc, 123, UID_2, MAX_PRICE, MAX_PRICE, 175, Bid, CommandResultCode::Success);
    fx.exp.remove_ask(0).remove_ask(0);
    fx.assert_l2();
    let ev = events(&cmd);
    assert_eq!(ev.len(), 3);
    check_trade(ev[0], 2, 81599, 50);
    check_trade(ev[1], 3, 81599, 25);
    check_trade(ev[2], 1, 81600, 100);
    let orders = fx.ob.find_user_orders(UID_1);
    for gone in [1, 2, 3] {
        assert!(!orders.iter().any(|o| o.order_id == gone), "order {gone} should have been removed");
    }
}

fn scn_should_match_ioc_order_with_rejection(fx: &mut Fixture) {
    let cmd = fx.newo(Ioc, 123, UID_2, MAX_PRICE, MAX_PRICE + 1, 270, Bid, CommandResultCode::Success);
    fx.exp.remove_all_asks();
    fx.assert_l2();
    let ev = events(&cmd);
    assert_eq!(ev.len(), 7);
    check_reject(ev[0], 25, MAX_PRICE, MAX_PRICE + 1);
}

fn scn_should_reject_fok_bid_order_out_of_budget(fx: &mut Fixture) {
    let size = 180;
    let buy_budget = fx.exp.aggregate_buy_budget(size) - 1;
    assert_eq!(buy_budget, 81599 * 75 + 81600 * 100 + 200954 * 5 - 1);
    let cmd = fx.newo(FokBudget, 123, UID_2, buy_budget, buy_budget, size, Bid, CommandResultCode::Success);
    fx.assert_l2();
    let ev = events(&cmd);
    assert_eq!(ev.len(), 1);
    check_reject(ev[0], size, buy_budget, buy_budget);
}

fn scn_should_match_fok_bid_order_exact_budget(fx: &mut Fixture) {
    let size = 180;
    let buy_budget = fx.exp.aggregate_buy_budget(size);
    assert_eq!(buy_budget, 81599 * 75 + 81600 * 100 + 200954 * 5);
    let cmd = fx.newo(FokBudget, 123, UID_2, buy_budget, buy_budget, size, Bid, CommandResultCode::Success);
    fx.exp.remove_ask(0).remove_ask(0).set_ask_volume(0, 5);
    fx.assert_l2();
    let ev = events(&cmd);
    assert_eq!(ev.len(), 4);
    check_trade(ev[0], 2, 81599, 50);
    check_trade(ev[1], 3, 81599, 25);
    check_trade(ev[2], 1, 81600, 100);
    check_trade(ev[3], 10, 200954, 5);
}

fn scn_should_match_fok_bid_order_extra_budget(fx: &mut Fixture) {
    let size = 176;
    let buy_budget = fx.exp.aggregate_buy_budget(size) + 1;
    assert_eq!(buy_budget, 81599 * 75 + 81600 * 100 + 200954 + 1);
    let cmd = fx.newo(FokBudget, 123, UID_2, buy_budget, buy_budget, size, Bid, CommandResultCode::Success);
    fx.exp.remove_ask(0).remove_ask(0).set_ask_volume(0, 9);
    fx.assert_l2();
    let ev = events(&cmd);
    assert_eq!(ev.len(), 4);
    check_trade(ev[0], 2, 81599, 50);
    check_trade(ev[1], 3, 81599, 25);
    check_trade(ev[2], 1, 81600, 100);
    check_trade(ev[3], 10, 200954, 1);
}

fn scn_should_reject_fok_ask_order_below_expectation(fx: &mut Fixture) {
    let size = 60;
    let sell_expectation = fx.exp.aggregate_sell_expectation(size) + 1;
    assert_eq!(sell_expectation, 81593 * 40 + 81590 * 20 + 1);
    let cmd = fx.newo(FokBudget, 123, UID_2, sell_expectation, sell_expectation, size, Ask, CommandResultCode::Success);
    fx.assert_l2();
    let ev = events(&cmd);
    assert_eq!(ev.len(), 1);
    check_reject(ev[0], size, sell_expectation, sell_expectation);
}

fn scn_should_match_fok_ask_order_exact_expectation(fx: &mut Fixture) {
    let size = 60;
    let sell_expectation = fx.exp.aggregate_sell_expectation(size);
    assert_eq!(sell_expectation, 81593 * 40 + 81590 * 20);
    let cmd = fx.newo(FokBudget, 123, UID_2, sell_expectation, sell_expectation, size, Ask, CommandResultCode::Success);
    fx.exp.remove_bid(0).set_bid_volume(0, 1).decrement_bid_orders_num(0);
    fx.assert_l2();
    let ev = events(&cmd);
    assert_eq!(ev.len(), 2);
    check_trade(ev[0], 4, 81593, 40);
    check_trade(ev[1], 5, 81590, 20);
}

fn scn_should_match_fok_ask_order_extra_budget(fx: &mut Fixture) {
    let size = 61;
    let sell_expectation = fx.exp.aggregate_sell_expectation(size) - 1;
    assert_eq!(sell_expectation, 81593 * 40 + 81590 * 21 - 1);
    let cmd = fx.newo(FokBudget, 123, UID_2, sell_expectation, sell_expectation, size, Ask, CommandResultCode::Success);
    fx.exp.remove_bid(0).remove_bid(0);
    fx.assert_l2();
    let ev = events(&cmd);
    assert_eq!(ev.len(), 3);
    check_trade(ev[0], 4, 81593, 40);
    check_trade(ev[1], 5, 81590, 20);
    check_trade(ev[2], 6, 81590, 1);
}

fn scn_should_fully_match_ioc_budget_with_sufficient_budget(fx: &mut Fixture) {
    let size = 180;
    let buy_budget = fx.exp.aggregate_buy_budget(size);
    let cmd = fx.newo(IocBudget, 123, UID_2, buy_budget, buy_budget, size, Bid, CommandResultCode::Success);
    fx.exp.remove_ask(0).remove_ask(0).set_ask_volume(0, 5);
    fx.assert_l2();
    let ev = events(&cmd);
    assert_eq!(ev.len(), 4);
    check_trade(ev[0], 2, 81599, 50);
    check_trade(ev[1], 3, 81599, 25);
    check_trade(ev[2], 1, 81600, 100);
    check_trade(ev[3], 10, 200954, 5);
}

fn scn_should_partially_match_ioc_budget_when_budget_runs_out(fx: &mut Fixture) {
    let size = 180;
    let buy_budget = 81599 * 75;
    let cmd = fx.newo(IocBudget, 123, UID_2, buy_budget, buy_budget, size, Bid, CommandResultCode::Success);
    fx.exp.remove_ask(0);
    fx.assert_l2();
    let ev = events(&cmd);
    assert_eq!(ev.len(), 3);
    check_reject(ev[0], 105, buy_budget, buy_budget);
    check_trade(ev[1], 2, 81599, 50);
    check_trade(ev[2], 3, 81599, 25);
}

fn scn_should_reject_ioc_budget_when_budget_too_small_for_one_unit(fx: &mut Fixture) {
    let size = 100;
    let buy_budget = 81598;
    let cmd = fx.newo(IocBudget, 123, UID_2, buy_budget, buy_budget, size, Bid, CommandResultCode::Success);
    fx.assert_l2();
    let ev = events(&cmd);
    assert_eq!(ev.len(), 1);
    check_reject(ev[0], size, buy_budget, buy_budget);
}

fn scn_should_reject_ask_ioc_budget(fx: &mut Fixture) {
    let size = 50;
    let sell_expectation = 81593 * 40;
    let cmd = fx.newo(IocBudget, 123, UID_2, sell_expectation, sell_expectation, size, Ask, CommandResultCode::Success);
    fx.assert_l2();
    let ev = events(&cmd);
    assert_eq!(ev.len(), 1);
    check_reject(ev[0], size, sell_expectation, sell_expectation);
}

fn scn_should_fully_match_marketable_gtc_order(fx: &mut Fixture) {
    let cmd = fx.newo(Gtc, 123, UID_2, 81599, MAX_PRICE, 1, Bid, CommandResultCode::Success);
    fx.exp.set_ask_volume(0, 74);
    fx.assert_l2();
    let ev = events(&cmd);
    assert_eq!(ev.len(), 1);
    check_trade(ev[0], 2, 81599, 1);
}

fn scn_should_partially_match_marketable_gtc_order_and_place(fx: &mut Fixture) {
    let cmd = fx.newo(Gtc, 123, UID_2, 81599, MAX_PRICE, 77, Bid, CommandResultCode::Success);
    fx.exp.remove_ask(0).insert_bid(0, 81599, 2);
    fx.assert_l2();
    let ev = events(&cmd);
    assert_eq!(ev.len(), 2);
    check_trade(ev[0], 2, 81599, 50);
    check_trade(ev[1], 3, 81599, 25);
}

fn scn_should_fully_match_marketable_gtc_order_2_prices(fx: &mut Fixture) {
    let cmd = fx.newo(Gtc, 123, UID_2, 81600, MAX_PRICE, 77, Bid, CommandResultCode::Success);
    fx.exp.remove_ask(0).set_ask_volume(0, 98);
    fx.assert_l2();
    let ev = events(&cmd);
    assert_eq!(ev.len(), 3);
    check_trade(ev[0], 2, 81599, 50);
    check_trade(ev[1], 3, 81599, 25);
    check_trade(ev[2], 1, 81600, 2);
}

fn scn_should_fully_match_marketable_gtc_order_with_all_liquidity(fx: &mut Fixture) {
    let cmd = fx.newo(Gtc, 123, UID_2, 220000, MAX_PRICE, 1000, Bid, CommandResultCode::Success);
    fx.exp.remove_all_asks().insert_bid(0, 220000, 755);
    fx.assert_l2();
    let ev = events(&cmd);
    assert_eq!(ev.len(), 6);
    check_trade(ev[0], 2, 81599, 50);
    check_trade(ev[1], 3, 81599, 25);
    check_trade(ev[2], 1, 81600, 100);
    check_trade(ev[3], 10, 200954, 10);
    check_trade(ev[4], 8, 201000, 28);
    check_trade(ev[5], 9, 201000, 32);
}

fn scn_should_move_order_fully_match_as_marketable(fx: &mut Fixture) {
    let cmd = fx.newo(Gtc, 83, UID_2, 81200, MAX_PRICE, 20, Bid, CommandResultCode::Success);
    assert_eq!(events(&cmd).len(), 0);
    fx.exp.set_bid_volume(2, 40).increment_bid_orders_num(2);
    fx.assert_l2();

    let cmd = fx.update(83, UID_2, 81602, CommandResultCode::Success);
    fx.exp.set_bid_volume(2, 20).decrement_bid_orders_num(2).set_ask_volume(0, 55);
    fx.assert_l2();
    let ev = events(&cmd);
    assert_eq!(ev.len(), 1);
    check_trade(ev[0], 2, 81599, 20);
}

fn scn_should_move_order_fully_match_as_marketable_2_prices(fx: &mut Fixture) {
    let cmd = fx.newo(Gtc, 83, UID_2, 81594, MAX_PRICE, 100, Bid, CommandResultCode::Success);
    assert_eq!(events(&cmd).len(), 0);

    let cmd = fx.update(83, UID_2, 81600, CommandResultCode::Success);
    fx.exp.remove_ask(0).set_ask_volume(0, 75);
    fx.assert_l2();
    let ev = events(&cmd);
    assert_eq!(ev.len(), 3);
    check_trade(ev[0], 2, 81599, 50);
    check_trade(ev[1], 3, 81599, 25);
    check_trade(ev[2], 1, 81600, 25);
}

fn scn_should_move_order_matches_all_liquidity(fx: &mut Fixture) {
    let cmd = fx.newo(Gtc, 83, UID_2, 81594, MAX_PRICE, 246, Bid, CommandResultCode::Success);
    assert_eq!(events(&cmd).len(), 0);

    let cmd = fx.update(83, UID_2, 201000, CommandResultCode::Success);
    fx.exp.remove_all_asks().insert_bid(0, 201000, 1);
    fx.assert_l2();
    let ev = events(&cmd);
    assert_eq!(ev.len(), 6);
    check_trade(ev[0], 2, 81599, 50);
    check_trade(ev[1], 3, 81599, 25);
    check_trade(ev[2], 1, 81600, 100);
    check_trade(ev[3], 10, 200954, 10);
    check_trade(ev[4], 8, 201000, 28);
    check_trade(ev[5], 9, 201000, 32);
}

macro_rules! parity {
    ( $( $name:ident => $scn:path ),+ $(,)? ) => {
        $(
            mod $name {
                use super::*;
                #[test]
                fn naive() {
                    let mut fx = Fixture::naive();
                    $scn(&mut fx);
                    fx.clear();
                }
                #[test]
                fn direct() {
                    let mut fx = Fixture::direct();
                    $scn(&mut fx);
                    fx.clear();
                }
            }
        )+
    };
}

parity! {
    should_initialize_without_errors => scn_should_initialize_without_errors,
    should_add_gtc_orders => scn_should_add_gtc_orders,
    should_ignored_duplicate_order => scn_should_ignored_duplicate_order,
    should_remove_bid_order => scn_should_remove_bid_order,
    should_remove_ask_order => scn_should_remove_ask_order,
    should_reduce_bid_order => scn_should_reduce_bid_order,
    should_reduce_ask_order => scn_should_reduce_ask_order,
    should_remove_order_and_empty_bucket => scn_should_remove_order_and_empty_bucket,
    should_return_error_when_deleting_unknown_order => scn_should_return_error_when_deleting_unknown_order,
    should_return_error_when_deleting_other_user_order => scn_should_return_error_when_deleting_other_user_order,
    should_return_error_when_updating_other_user_order => scn_should_return_error_when_updating_other_user_order,
    should_return_error_when_updating_unknown_order => scn_should_return_error_when_updating_unknown_order,
    should_return_error_when_reducing_unknown_order => scn_should_return_error_when_reducing_unknown_order,
    should_return_error_when_reducing_by_zero_or_negative_size => scn_should_return_error_when_reducing_by_zero_or_negative_size,
    should_return_error_when_reducing_other_user_order => scn_should_return_error_when_reducing_other_user_order,
    should_move_order_existing_bucket => scn_should_move_order_existing_bucket,
    should_move_order_new_bucket => scn_should_move_order_new_bucket,
    should_match_ioc_order_partial_bbo => scn_should_match_ioc_order_partial_bbo,
    should_match_ioc_order_full_bbo => scn_should_match_ioc_order_full_bbo,
    should_match_ioc_order_with_two_limit_orders_partial => scn_should_match_ioc_order_with_two_limit_orders_partial,
    should_match_ioc_order_full_liquidity => scn_should_match_ioc_order_full_liquidity,
    should_match_ioc_order_with_rejection => scn_should_match_ioc_order_with_rejection,
    should_reject_fok_bid_order_out_of_budget => scn_should_reject_fok_bid_order_out_of_budget,
    should_match_fok_bid_order_exact_budget => scn_should_match_fok_bid_order_exact_budget,
    should_match_fok_bid_order_extra_budget => scn_should_match_fok_bid_order_extra_budget,
    should_reject_fok_ask_order_below_expectation => scn_should_reject_fok_ask_order_below_expectation,
    should_match_fok_ask_order_exact_expectation => scn_should_match_fok_ask_order_exact_expectation,
    should_match_fok_ask_order_extra_budget => scn_should_match_fok_ask_order_extra_budget,
    should_fully_match_ioc_budget_with_sufficient_budget => scn_should_fully_match_ioc_budget_with_sufficient_budget,
    should_partially_match_ioc_budget_when_budget_runs_out => scn_should_partially_match_ioc_budget_when_budget_runs_out,
    should_reject_ioc_budget_when_budget_too_small_for_one_unit => scn_should_reject_ioc_budget_when_budget_too_small_for_one_unit,
    should_reject_ask_ioc_budget => scn_should_reject_ask_ioc_budget,
    should_fully_match_marketable_gtc_order => scn_should_fully_match_marketable_gtc_order,
    should_partially_match_marketable_gtc_order_and_place => scn_should_partially_match_marketable_gtc_order_and_place,
    should_fully_match_marketable_gtc_order_2_prices => scn_should_fully_match_marketable_gtc_order_2_prices,
    should_fully_match_marketable_gtc_order_with_all_liquidity => scn_should_fully_match_marketable_gtc_order_with_all_liquidity,
    should_move_order_fully_match_as_marketable => scn_should_move_order_fully_match_as_marketable,
    should_move_order_fully_match_as_marketable_2_prices => scn_should_move_order_fully_match_as_marketable_2_prices,
    should_move_order_matches_all_liquidity => scn_should_move_order_matches_all_liquidity,
}
