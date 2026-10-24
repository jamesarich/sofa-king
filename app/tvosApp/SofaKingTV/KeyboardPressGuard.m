#import <UIKit/UIKit.h>
#import <objc/runtime.h>

// tvOS's keyboard can end up with no key under its cursor; a remote arrow pressed then throws NSRangeException
// (index NSNotFound) inside -[UIKeyboardLayoutCursor handleVisualDirectionalInput:], which UITextField's
// pressesBegan and pressesEnded reach through -[UIKeyboard canHandlePresses:withEvent:], and the app stops. Swift
// cannot catch an Objective-C exception, so this wraps each of UITextField's press handlers: that exception drops the
// press and closes the keyboard, whose state is then not to be trusted, and the field keeps what was typed.
@implementation UITextField (SofaKingKeyboardPressGuard)

// Puts guarded's implementation in place of original's on UITextField itself. A handler UITextField only inherits is
// added to it first, so UIResponder's own stays as it is for every other responder.
static void SofaKingGuard(Class cls, SEL original, SEL guarded) {
    Method originalMethod = class_getInstanceMethod(cls, original);
    Method guardedMethod = class_getInstanceMethod(cls, guarded);
    if (!originalMethod || !guardedMethod) return;
    if (class_addMethod(cls, original, method_getImplementation(guardedMethod), method_getTypeEncoding(guardedMethod))) {
        class_replaceMethod(cls, guarded, method_getImplementation(originalMethod), method_getTypeEncoding(originalMethod));
    } else {
        method_exchangeImplementations(originalMethod, guardedMethod);
    }
}

+ (void)load {
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        SofaKingGuard(self, @selector(pressesBegan:withEvent:), @selector(sofaking_pressesBegan:withEvent:));
        SofaKingGuard(self, @selector(pressesChanged:withEvent:), @selector(sofaking_pressesChanged:withEvent:));
        SofaKingGuard(self, @selector(pressesEnded:withEvent:), @selector(sofaking_pressesEnded:withEvent:));
        SofaKingGuard(self, @selector(pressesCancelled:withEvent:), @selector(sofaking_pressesCancelled:withEvent:));
    });
}

- (void)sofaking_guardPress:(void (^)(void))press {
    @try {
        press();
    } @catch (NSException *exception) {
        if (![exception.name isEqualToString:NSRangeException]) @throw;
        dispatch_async(dispatch_get_main_queue(), ^{ [self resignFirstResponder]; });
    }
}

// Swapped at load, so each of these calls UIKit's own handler.
- (void)sofaking_pressesBegan:(NSSet<UIPress *> *)presses withEvent:(UIPressesEvent *)event {
    [self sofaking_guardPress:^{ [self sofaking_pressesBegan:presses withEvent:event]; }];
}

- (void)sofaking_pressesChanged:(NSSet<UIPress *> *)presses withEvent:(UIPressesEvent *)event {
    [self sofaking_guardPress:^{ [self sofaking_pressesChanged:presses withEvent:event]; }];
}

- (void)sofaking_pressesEnded:(NSSet<UIPress *> *)presses withEvent:(UIPressesEvent *)event {
    [self sofaking_guardPress:^{ [self sofaking_pressesEnded:presses withEvent:event]; }];
}

- (void)sofaking_pressesCancelled:(NSSet<UIPress *> *)presses withEvent:(UIPressesEvent *)event {
    [self sofaking_guardPress:^{ [self sofaking_pressesCancelled:presses withEvent:event]; }];
}

@end
