use crate::core::common::cmd::order_command::OrderCommand;

#[derive(Default)]
pub struct CommandSubmitter(Option<Box<dyn FnMut(OrderCommand)>>);

impl CommandSubmitter {

    pub fn set(&mut self, cb: Box<dyn FnMut(OrderCommand)>) {
        self.0 = Some(cb);
    }

    pub fn submit(&mut self, cmd: OrderCommand) {
        if let Some(cb) = &mut self.0 {
            cb(cmd);
        }
    }
}

impl std::fmt::Debug for CommandSubmitter {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_tuple("CommandSubmitter")
            .field(&self.0.as_ref().map(|_| "<fn>"))
            .finish()
    }
}
