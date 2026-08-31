//! 账户 / 规格注册表。对应 Java `exchange.core2.core.common.UserProfile` +
//! `exchange.core2.core.processors.{SymbolSpecificationProvider, UserProfileService}`（现货子集）。
pub mod profile;
pub mod registry;

pub use profile::UserProfile;
pub use registry::{SymbolSpecificationProvider, UserProfileService};
